package main

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"strings"
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

func TestSinceReturnsLatestNewerVersions(t *testing.T) {
	s := mustOpen(t, t.TempDir())
	defer mustClose(t, s)
	err := s.Append([]Version{
		{ID: "a", Rev: 1, Blob: "old"},
		{ID: "b", Rev: 2, Blob: "B"},
		{ID: "a", Rev: 3, Blob: "new"},
		{ID: "c", Rev: 4, Blob: "C"},
	})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}

	got := s.Since(1)
	want := []Version{
		{ID: "b", Rev: 2, Blob: "B"},
		{ID: "a", Rev: 3, Blob: "new"},
		{ID: "c", Rev: 4, Blob: "C"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(1) = %+v, want %+v", got, want)
	}

	got = s.Since(3)
	want = []Version{{ID: "c", Rev: 4, Blob: "C"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Since(3) = %+v, want %+v", got, want)
	}
}

func TestTornLastLineIsDropped(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	err := s.Append([]Version{{ID: "a", Rev: 1, Blob: "A"}, {ID: "b", Rev: 2, Blob: "B"}})
	if err != nil {
		t.Fatalf("Append: %v", err)
	}
	mustClose(t, s)

	logPath := filepath.Join(dir, logName)
	f, err := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatalf("open log: %v", err)
	}
	if _, err := f.WriteString(`{"id":"x","re`); err != nil {
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

	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	if bytes.HasSuffix(data, []byte(`{"id":"x","re`)) {
		t.Fatal("log still ends with the torn bytes")
	}
	if lines := bytes.Count(data, []byte("\n")); lines != 2 {
		t.Fatalf("log has %d lines, want 2", lines)
	}
}

func TestBadMiddleLineIsError(t *testing.T) {
	dir := t.TempDir()
	content := `{"id":"a","rev":1}` + "\n" + "not json\n" + `{"id":"b","rev":2}` + "\n"
	if err := os.WriteFile(filepath.Join(dir, logName), []byte(content), 0600); err != nil {
		t.Fatalf("write log: %v", err)
	}
	s, err := OpenStore(dir)
	if err == nil {
		mustClose(t, s)
		t.Fatal("OpenStore accepted a corrupt middle line")
	}
}

func TestCompactionKeepsOneLinePerRecord(t *testing.T) {
	dir := t.TempDir()
	s := mustOpen(t, dir)
	for i := 1; i <= 150; i++ {
		err := s.Append([]Version{{ID: "a", Rev: int64(i), Blob: "AA"}})
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
	if lines := strings.Count(string(data), "\n"); lines != 1 {
		t.Fatalf("log has %d lines after compaction, want 1", lines)
	}
	v, ok := s2.Get("a")
	if !ok || v.Rev != 150 {
		t.Fatalf("Get(a) = %+v, %v; want rev 150", v, ok)
	}
	if s2.Rev() != 150 {
		t.Fatalf("Rev() = %d, want 150", s2.Rev())
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
