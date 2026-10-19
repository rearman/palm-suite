package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
)

const (
	logName    = "log.jsonl"
	metaName   = "meta.json"
	tokensName = "tokens.json"
	maxLine    = 1 << 20
)

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

// Store keeps the record log in dir/log.jsonl and the meta in dir/meta.json.
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
func OpenStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	s := &Store{dir: dir, live: make(map[string]Version)}
	logPath := filepath.Join(dir, logName)
	good, err := s.replay(logPath)
	if err != nil {
		return nil, err
	}
	if good > 100 && good > 2*len(s.live) {
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
	return s, nil
}

// replay loads the log into s.live and s.rev. It returns the number of good lines.
// A bad last line is a torn write: it is dropped and the file is cut back to the last good line.
// A line without its newline was never acknowledged by Append, so it counts as torn too.
func (s *Store) replay(logPath string) (int, error) {
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

// compact rewrites the log with one line per live version, sorted by Rev.
func (s *Store) compact(logPath string) error {
	vs := make([]Version, 0, len(s.live))
	for _, v := range s.live {
		vs = append(vs, v)
	}
	sortByRev(vs)
	data, err := encodeLines(vs)
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
	data, err := encodeLines(vs)
	if err != nil {
		return err
	}
	if _, err := s.log.Write(data); err != nil {
		s.log.Truncate(s.size) // drop a partial write, so the next line does not follow bad bytes
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

// encodeLines writes each version as one JSON line into one buffer.
func encodeLines(vs []Version) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	for _, v := range vs {
		if err := enc.Encode(v); err != nil {
			return nil, err
		}
	}
	return buf.Bytes(), nil
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
