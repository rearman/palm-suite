package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func mustOpen(t *testing.T, dir string) *Store {
	t.Helper()
	s, err := OpenStore(dir)
	if err != nil {
		t.Fatalf("OpenStore: %v", err)
	}
	return s
}

func mustClose(t *testing.T, s *Store) {
	t.Helper()
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

// logBytes returns a whole log file for vs: the magic, then one record for each version.
func logBytes(t *testing.T, vs ...Version) []byte {
	t.Helper()
	data, err := encodeRecords([]byte(logMagic), vs)
	if err != nil {
		t.Fatalf("encodeRecords: %v", err)
	}
	return data
}

func TestAppendSurvivesReopen(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	err := s.Append([]Version{{ID: "a", Rev: 1, Blob: "AAAA"}, {ID: "b", Rev: 2, Blob: "BBBB"}})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}
	mustClose(t, s)

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	if s2.Rev() != 2 {
		t.Fatalf("Rev() = %d, want 2", s2.Rev())
	}
	v, ok := s2.Get("a")
	if !ok || v.Rev != 1 || v.Blob != "AAAA" {
		t.Fatalf("Get(a) = %+v, %v; want rev 1 blob AAAA", v, ok)
	}
}

func TestStoreKeepsLiveAndTombstonesAcrossReopen(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	err := s.Append([]Version{
		{ID: "a", Rev: 1, Blob: "AAAA"},
		{ID: "b", Rev: 2, Blob: "BBBB"},
		{ID: "a", Rev: 3, Deleted: true},
		{ID: "c", Rev: 4, Blob: "CCCC"},
	})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}
	mustClose(t, s)

	// The log holds four records, one for each version written.
	logPath := filepath.Join(dir, logName)
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	recs, good, err := scanLog(logPath, data)
	if err != nil {
		t.Fatalf("scanLog: %v", err)
	}
	if len(recs) != 4 || good != len(data) {
		t.Fatalf("log has %d records and %d good of %d bytes, want 4 and all", len(recs), good, len(data))
	}

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	if s2.Rev() != 4 {
		t.Fatalf("Rev() = %d, want 4", s2.Rev())
	}
	want := []Version{
		{ID: "b", Rev: 2, Blob: "BBBB"},
		{ID: "a", Rev: 3, Deleted: true},
		{ID: "c", Rev: 4, Blob: "CCCC"},
	}
	if got := s2.Since(0); !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(0) = %+v, want %+v", got, want)
	}
	v, ok := s2.Get("a")
	if !ok || !v.Deleted || v.Rev != 3 {
		t.Fatalf("Get(a) = %+v, %v; want tombstone at rev 3", v, ok)
	}
}

func TestSinceReturnsLatestNewerVersions(t *testing.T) {
	s := mustOpen(t, t.TempDir())
	defer mustClose(t, s)
	err := s.Append([]Version{
		{ID: "a", Rev: 1, Blob: "AAAA"},
		{ID: "b", Rev: 2, Blob: "BBBB"},
		{ID: "a", Rev: 3, Blob: "CCCC"},
		{ID: "c", Rev: 4, Blob: "DDDD"},
	})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}

	got := s.Since(1)
	want := []Version{
		{ID: "b", Rev: 2, Blob: "BBBB"},
		{ID: "a", Rev: 3, Blob: "CCCC"},
		{ID: "c", Rev: 4, Blob: "DDDD"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(1) = %+v, want %+v", got, want)
	}

	got = s.Since(3)
	want = []Version{{ID: "c", Rev: 4, Blob: "DDDD"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(3) = %+v, want %+v", got, want)
	}
}

func TestTornLastRecordIsDropped(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	err := s.Append([]Version{{ID: "a", Rev: 1, Blob: "AAAA"}, {ID: "b", Rev: 2, Blob: "BBBB"}})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}
	mustClose(t, s)

	// Write the first half of a third record, as a crash in the middle of Append would leave it.
	torn, err := encodeRecords(nil, []Version{{ID: "x", Rev: 3, Blob: "CCCC"}})
	if err != nil {
		t.Fatalf("encodeRecords: %v", err)
	}
	logPath := filepath.Join(dir, logName)
	f, err := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatalf("open log: %v", err)
	}
	if _, err := f.Write(torn[:len(torn)/2]); err != nil {
		t.Fatalf("write torn bytes: %v", err)
	}
	if err := f.Close(); err != nil {
		t.Fatalf("close log: %v", err)
	}

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	if _, ok := s2.Get("a"); !ok {
		t.Fatal("record a is missing")
	}
	if _, ok := s2.Get("b"); !ok {
		t.Fatal("record b is missing")
	}
	if _, ok := s2.Get("x"); ok {
		t.Fatal("torn record x was loaded")
	}
	if s2.Rev() != 2 {
		t.Fatalf("Rev() = %d, want 2", s2.Rev())
	}

	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	want := logBytes(t, Version{ID: "a", Rev: 1, Blob: "AAAA"}, Version{ID: "b", Rev: 2, Blob: "BBBB"})
	if !bytes.Equal(data, want) {
		t.Fatalf("log is %d bytes after reopen, want the %d good bytes", len(data), len(want))
	}
}

func TestBadMiddleRecordIsError(t *testing.T) {
	dir := t.TempDir()
	good1, err := encodeRecords(nil, []Version{{ID: "a", Rev: 1, Blob: "AAAA"}})
	if err != nil {
		t.Fatalf("encodeRecords: %v", err)
	}
	good2, err := encodeRecords(nil, []Version{{ID: "b", Rev: 2, Blob: "BBBB"}})
	if err != nil {
		t.Fatalf("encodeRecords: %v", err)
	}
	// A body of 2 bytes that claims a 5 byte id.
	bad := binary.AppendUvarint(nil, 2)
	bad = append(bad, 0x05, 'x')

	data := append([]byte(logMagic), good1...)
	data = append(data, bad...)
	data = append(data, good2...)
	if err := os.WriteFile(filepath.Join(dir, logName), data, 0600); err != nil {
		t.Fatalf("write log: %v", err)
	}
	s, err := OpenStore(dir)
	if err == nil {
		mustClose(t, s)
		t.Fatal("OpenStore accepted a corrupt middle record")
	}
}

func TestCompactionKeepsOneRecordPerRecord(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	for i := 1; i <= 150; i++ {
		err := s.Append([]Version{{ID: "a", Rev: int64(i), Blob: "AAAA"}})
		if err != nil {
			t.Fatalf("Append rev %d: %v", i, err)
		}
	}
	mustClose(t, s)

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	data, err := os.ReadFile(filepath.Join(dir, logName))
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	want := logBytes(t, Version{ID: "a", Rev: 150, Blob: "AAAA"})
	if !bytes.Equal(data, want) {
		t.Fatalf("log after compaction is %d bytes, want one record of %d bytes", len(data), len(want))
	}
	v, ok := s2.Get("a")
	if !ok || v.Rev != 150 {
		t.Fatalf("Get(a) = %+v, %v; want rev 150", v, ok)
	}
	if s2.Rev() != 150 {
		t.Fatalf("Rev() = %d, want 150", s2.Rev())
	}
}

func TestOldJSONLogIsMigrated(t *testing.T) {
	dir := t.TempDir()
	old := `{"id":"a","rev":1,"deleted":false,"blob":"AAAA"}` + "\n" +
		`{"id":"b","rev":2,"deleted":true}` + "\n" +
		`{"id":"a","rev":3,"deleted":false,"blob":"BBBB"}` + "\n"
	jsonPath := filepath.Join(dir, legacyLogName)
	if err := os.WriteFile(jsonPath, []byte(old), 0600); err != nil {
		t.Fatalf("write old log: %v", err)
	}

	s := mustOpen(t, dir)
	if s.Rev() != 3 {
		t.Fatalf("Rev() = %d, want 3", s.Rev())
	}
	want := []Version{
		{ID: "b", Rev: 2, Deleted: true},
		{ID: "a", Rev: 3, Blob: "BBBB"},
	}
	if got := s.Since(0); !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(0) = %+v, want %+v", got, want)
	}
	mustClose(t, s)

	for _, name := range []string{logName, legacyLogName + ".bak"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatalf("%s is missing after migration: %v", name, err)
		}
	}
	if _, err := os.Stat(jsonPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("%s is still there after migration: %v", legacyLogName, err)
	}

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	if s2.Rev() != 3 {
		t.Fatalf("Rev() after reopen = %d, want 3", s2.Rev())
	}
	if got := s2.Since(0); !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(0) after reopen = %+v, want %+v", got, want)
	}
}

func TestMetaAndTokensRoundTrip(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	meta := Meta{Salt: "c2FsdA==", Check: "Y2hlY2s="}
	if err := s.SetMeta(meta); err != nil {
		t.Fatalf("SetMeta: %v", err)
	}
	mustClose(t, s)

	s2 := mustOpen(t, dir)
	defer mustClose(t, s2)
	if got := s2.Meta(); got != meta {
		t.Fatalf("Meta() = %+v, want %+v", got, meta)
	}

	ts, err := LoadTokens(dir)
	if err != nil || ts != nil {
		t.Fatalf("LoadTokens on missing file = %+v, %v; want nil, nil", ts, err)
	}
	want := []Token{{Name: "palm", Hash: "abcd"}, {Name: "laptop", Hash: "ef01"}}
	if err := SaveTokens(dir, want); err != nil {
		t.Fatalf("SaveTokens: %v", err)
	}
	got, err := LoadTokens(dir)
	if err != nil {
		t.Fatalf("LoadTokens: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("LoadTokens = %+v, want %+v", got, want)
	}
}
