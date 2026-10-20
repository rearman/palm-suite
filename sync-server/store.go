package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"sort"
)

const (
	logName       = "log.bin"
	legacyLogName = "log.jsonl"
	metaName      = "meta.json"
	tokensName    = "tokens.json"
	maxLine       = 1 << 20 // limit for one record body
	logMagic      = "PSL1"
)

// flagDeleted is bit 0 of the flags byte of a record. It marks a tombstone.
const flagDeleted = 1 << 0

// Version is one stored version of a record.
type Version struct {
	ID      string `json:"id"`
	Rev     int64  `json:"rev"`
	Deleted bool   `json:"deleted"`
	Blob    string `json:"blob,omitempty"` // base64 ciphertext; empty for a tombstone
}

// Meta holds the key salt and the passphrase check blob that the first device sets.
type Meta struct {
	Salt  string `json:"salt"`
	Check string `json:"check"`
}

// Token is one device token. Hash is the hex SHA-256 of the token.
type Token struct {
	Name string `json:"name"`
	Hash string `json:"hash"`
}

// Store keeps the record log in dir/log.bin and the meta in dir/meta.json.
// Store is not safe for concurrent use. The HTTP layer must hold a lock around every call.
// Tombstones are kept for good: a device that was offline for a long time still learns of each delete.
type Store struct {
	dir  string
	log  *os.File
	size int64 // length of the log after the last good append
	rev  int64
	meta Meta

	// Shortcut: all live versions are in memory and Since scans them all.
	// This is fine for thousands of records. Upgrade path: SQLite with an index on rev.
	live map[string]Version
}

// OpenStore opens the store in dir, creating it when it does not exist.
// An old store with only log.jsonl is migrated to log.bin first.
func OpenStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	s := &Store{dir: dir, live: make(map[string]Version)}
	logPath := filepath.Join(dir, logName)
	if _, err := os.Stat(logPath); errors.Is(err, os.ErrNotExist) {
		if err := s.migrateJSON(filepath.Join(dir, legacyLogName), logPath); err != nil {
			return nil, err
		}
	}
	n, err := s.replay(logPath)
	if err != nil {
		return nil, err
	}
	if n > 100 && n > 2*len(s.live) {
		if err := s.compact(logPath); err != nil {
			return nil, err
		}
	}
	if err := readJSON(filepath.Join(dir, metaName), &s.meta); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	s.log = f
	s.size = st.Size()
	if s.size == 0 {
		// A new log, or a log whose header was torn: write the magic first.
		if _, err := f.Write([]byte(logMagic)); err != nil {
			f.Close()
			return nil, err
		}
		if err := f.Sync(); err != nil {
			f.Close()
			return nil, err
		}
		s.size = int64(len(logMagic))
	}
	return s, nil
}

// migrateJSON moves an old JSON-lines log to logPath. It does nothing when oldPath does not exist.
// The old file is kept as oldPath + ".bak".
func (s *Store) migrateJSON(oldPath, logPath string) error {
	_, err := os.Stat(oldPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	tmp := &Store{live: make(map[string]Version)}
	if _, err := tmp.replayJSON(oldPath); err != nil {
		return err
	}
	if err := tmp.compact(logPath); err != nil {
		return err
	}
	if err := os.Rename(oldPath, oldPath+".bak"); err != nil {
		return err
	}
	if err := syncDir(s.dir); err != nil {
		return err
	}
	log.Printf("sync: moved %d versions from %s to %s", len(tmp.live), oldPath, logPath)
	return nil
}

// replay loads the log into s.live and s.rev. It returns the number of good records.
// A torn last record is dropped and the file is cut back to the last good record.
func (s *Store) replay(logPath string) (int, error) {
	data, err := os.ReadFile(logPath)
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	// Shortcut: the whole log is read into memory. This is fine for the record counts
	// this server holds. Upgrade path: read the file in a stream.
	vs, good, err := scanLog(logPath, data)
	if err != nil {
		return 0, err
	}
	if good < len(data) {
		if err := os.Truncate(logPath, int64(good)); err != nil {
			return 0, err
		}
	}
	for _, v := range vs {
		s.live[v.ID] = v
		if v.Rev > s.rev {
			s.rev = v.Rev
		}
	}
	return len(vs), nil
}

// scanLog parses the records in data, which holds a whole log file. It returns the good
// records and the length of the good prefix. A record that is cut short at the end, or
// that is broken as the last record, is a torn write. A broken record with data after it
// is an error.
func scanLog(path string, data []byte) ([]Version, int, error) {
	if len(data) < len(logMagic) {
		if string(data) != logMagic[:len(data)] {
			return nil, 0, fmt.Errorf("%s: not a sync log", path)
		}
		return nil, 0, nil // torn header
	}
	if string(data[:len(logMagic)]) != logMagic {
		return nil, 0, fmt.Errorf("%s: not a sync log", path)
	}
	var vs []Version
	good := len(logMagic)
	for good < len(data) {
		n, k := binary.Uvarint(data[good:])
		if k == 0 {
			break // the length is cut short: torn
		}
		if k < 0 || n > maxLine {
			return nil, 0, fmt.Errorf("%s: record at byte %d has a bad length", path, good)
		}
		end := good + k + int(n)
		if end > len(data) {
			break // the body is cut short: torn
		}
		v, err := decodeRecord(data[good+k : end])
		if err != nil {
			if end == len(data) {
				break // the last record is broken: torn
			}
			return nil, 0, fmt.Errorf("%s: record at byte %d is corrupt: %w", path, good, err)
		}
		vs = append(vs, v)
		good = end
	}
	return vs, good, nil
}

// replayJSON loads an old JSON-lines log into s.live and s.rev. It is used only to migrate.
// A bad last line is a torn write: it is dropped and the file is cut back to the last good line.
// A line without its newline was never acknowledged by Append, so it counts as torn too.
func (s *Store) replayJSON(logPath string) (int, error) {
	f, err := os.Open(logPath)
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return 0, err
	}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), maxLine)
	var (
		lines   int   // lines read, good or bad
		good    int   // lines that parsed
		start   int64 // offset where the current line starts
		goodEnd int64 // offset just past the last good line
		badAt   int   // number of the first bad line, 0 when none
	)
	for sc.Scan() {
		lines++
		if badAt != 0 {
			return 0, fmt.Errorf("%s: line %d is corrupt", logPath, badAt)
		}
		line := sc.Bytes()
		end := start + int64(len(line)) + 1
		var v Version
		if end > st.Size() || json.Unmarshal(line, &v) != nil {
			badAt = lines
		} else {
			s.live[v.ID] = v
			if v.Rev > s.rev {
				s.rev = v.Rev
			}
			good++
			goodEnd = end
		}
		start = end
	}
	err = sc.Err()
	f.Close()
	if err != nil {
		return 0, fmt.Errorf("%s: %w", logPath, err)
	}
	if badAt != 0 {
		if err := os.Truncate(logPath, goodEnd); err != nil {
			return 0, err
		}
	}
	return good, nil
}

// compact rewrites the log with one record per live version, sorted by Rev.
func (s *Store) compact(logPath string) error {
	vs := make([]Version, 0, len(s.live))
	for _, v := range s.live {
		vs = append(vs, v)
	}
	sortByRev(vs)
	data, err := encodeRecords([]byte(logMagic), vs)
	if err != nil {
		return err
	}
	return writeFileAtomic(logPath, data, 0600)
}

// Close closes the log file.
func (s *Store) Close() error {
	return s.log.Close()
}

// Rev returns the highest rev in the store, 0 when empty.
func (s *Store) Rev() int64 {
	return s.rev
}

// Get returns the latest version of the record with the given id.
func (s *Store) Get(id string) (Version, bool) {
	v, ok := s.live[id]
	return v, ok
}

// Since returns the latest version of each record with a Rev above rev, sorted by Rev.
func (s *Store) Since(rev int64) []Version {
	out := []Version{}
	for _, v := range s.live {
		if v.Rev > rev {
			out = append(out, v)
		}
	}
	sortByRev(out)
	return out
}

// Append writes vs to the log and fsyncs it. The map and Rev change only after the write succeeds.
// The caller sets each Rev.
func (s *Store) Append(vs []Version) error {
	if len(vs) == 0 {
		return nil
	}
	data, err := encodeRecords(nil, vs)
	if err != nil {
		return err
	}
	if _, err := s.log.Write(data); err != nil {
		s.log.Truncate(s.size) // drop a partial write, so the next record does not follow bad bytes
		return err
	}
	if err := s.log.Sync(); err != nil {
		s.log.Truncate(s.size)
		return err
	}
	s.size += int64(len(data))
	for _, v := range vs {
		s.live[v.ID] = v
		if v.Rev > s.rev {
			s.rev = v.Rev
		}
	}
	return nil
}

// Meta returns the meta loaded at open, or the last value set by SetMeta.
func (s *Store) Meta() Meta {
	return s.meta
}

// SetMeta saves m to dir/meta.json.
func (s *Store) SetMeta(m Meta) error {
	data, err := json.Marshal(m)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(filepath.Join(s.dir, metaName), data, 0600); err != nil {
		return err
	}
	s.meta = m
	return nil
}

// LoadTokens reads dir/tokens.json. A missing file gives nil, nil.
func LoadTokens(dir string) ([]Token, error) {
	var ts []Token
	if err := readJSON(filepath.Join(dir, tokensName), &ts); err != nil {
		return nil, err
	}
	return ts, nil
}

// SaveTokens writes ts to dir/tokens.json.
func SaveTokens(dir string, ts []Token) error {
	data, err := json.Marshal(ts)
	if err != nil {
		return err
	}
	return writeFileAtomic(filepath.Join(dir, tokensName), data, 0600)
}

// sortByRev orders versions by Rev, then by ID so the order is stable.
func sortByRev(vs []Version) {
	sort.Slice(vs, func(i, j int) bool {
		if vs[i].Rev != vs[j].Rev {
			return vs[i].Rev < vs[j].Rev
		}
		return vs[i].ID < vs[j].ID
	})
}

// encodeRecords appends one record for each version to buf.
func encodeRecords(buf []byte, vs []Version) ([]byte, error) {
	for _, v := range vs {
		var err error
		buf, err = appendRecord(buf, v)
		if err != nil {
			return nil, err
		}
	}
	return buf, nil
}

// appendRecord appends one record to buf: a uvarint body length, then the body.
// The body holds the id, the rev, a flags byte, and the blob as raw bytes.
func appendRecord(buf []byte, v Version) ([]byte, error) {
	blob, err := base64.StdEncoding.DecodeString(v.Blob)
	if err != nil {
		return nil, fmt.Errorf("record %q: blob is not base64: %w", v.ID, err)
	}
	var body []byte
	body = binary.AppendUvarint(body, uint64(len(v.ID)))
	body = append(body, v.ID...)
	body = binary.AppendVarint(body, v.Rev)
	var flags byte
	if v.Deleted {
		flags |= flagDeleted
	}
	body = append(body, flags)
	body = binary.AppendUvarint(body, uint64(len(blob)))
	body = append(body, blob...)
	if len(body) > maxLine {
		return nil, fmt.Errorf("record %q is %d bytes, over the %d byte limit", v.ID, len(body), maxLine)
	}
	buf = binary.AppendUvarint(buf, uint64(len(body)))
	return append(buf, body...), nil
}

// decodeRecord parses one record body. It fails when the body does not hold exactly one record.
func decodeRecord(body []byte) (Version, error) {
	var v Version
	r := bytes.NewReader(body)
	idLen, err := binary.ReadUvarint(r)
	if err != nil {
		return v, err
	}
	if idLen > uint64(r.Len()) {
		return v, errors.New("id runs past the end of the record")
	}
	id := make([]byte, idLen)
	if _, err := io.ReadFull(r, id); err != nil {
		return v, err
	}
	v.ID = string(id)
	if v.Rev, err = binary.ReadVarint(r); err != nil {
		return v, err
	}
	flags, err := r.ReadByte()
	if err != nil {
		return v, err
	}
	if flags&^flagDeleted != 0 {
		return v, fmt.Errorf("unknown flags %#x", flags)
	}
	v.Deleted = flags&flagDeleted != 0
	blobLen, err := binary.ReadUvarint(r)
	if err != nil {
		return v, err
	}
	if blobLen > uint64(r.Len()) {
		return v, errors.New("blob runs past the end of the record")
	}
	blob := make([]byte, blobLen)
	if _, err := io.ReadFull(r, blob); err != nil {
		return v, err
	}
	if r.Len() != 0 {
		return v, errors.New("bytes after the blob")
	}
	v.Blob = base64.StdEncoding.EncodeToString(blob)
	return v, nil
}

// readJSON decodes the file at path into v. A missing file leaves v unchanged and returns nil.
func readJSON(path string, v any) error {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	return json.Unmarshal(data, v)
}

// writeFileAtomic writes data to a temp file, fsyncs it, renames it over path, and fsyncs the directory.
func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, perm)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	return syncDir(filepath.Dir(path))
}

// syncDir flushes a directory change, such as a rename, to disk.
// Windows cannot fsync a directory, so the error is ignored there.
func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	if err := d.Sync(); err != nil && runtime.GOOS != "windows" {
		return err
	}
	return nil
}
