package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
)

const (
	maxBodyBytes = 16 << 20
	maxIDBytes   = 64
	maxBlobBytes = 64 << 10
)

// Server serves the sync protocol of PROTOCOL.md over HTTP.
type Server struct {
	mu    sync.Mutex // Shortcut: one lock for all requests. Fine for one user; upgrade path: a lock per store.
	store *Store
	dir   string // data dir, for tokens.json
}

type pushItem struct {
	ID      string `json:"id"`
	BaseRev int64  `json:"baseRev"`
	Deleted bool   `json:"deleted"`
	Blob    string `json:"blob"`
}

type syncRequest struct {
	Since int64      `json:"since"`
	Push  []pushItem `json:"push"`
}

type accepted struct {
	ID  string `json:"id"`
	Rev int64  `json:"rev"`
}

type syncResponse struct {
	Rev       int64      `json:"rev"`
	Accepted  []accepted `json:"accepted"`
	Conflicts []string   `json:"conflicts"`
	Changes   []Version  `json:"changes"`
}

// NewServer returns a server that reads and writes st and reads tokens.json
// from dir.
func NewServer(st *Store, dir string) *Server {
	return &Server{store: st, dir: dir}
}

// Handler returns the HTTP routes. Every route requires a bearer token.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/meta", s.guard(s.handleGetMeta))
	mux.HandleFunc("PUT /v1/meta", s.guard(s.handlePutMeta))
	mux.HandleFunc("POST /v1/sync", s.guard(s.handleSync))
	return mux
}

// guard checks the bearer token, then limits the request body and calls next.
func (s *Server) guard(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ok, err := s.authorized(r)
		if err != nil {
			log.Printf("sync: auth: %v", err)
			respondError(w, http.StatusInternalServerError, "internal error")
			return
		}
		if !ok {
			respondError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		next(w, r)
	}
}

// authorized reports whether the request carries the token of a known device.
// The tokens file is read on each request, so a token added by the CLI works
// at once.
func (s *Server) authorized(r *http.Request) (bool, error) {
	token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		return false, nil
	}
	sum := sha256.Sum256([]byte(token))
	given := []byte(hex.EncodeToString(sum[:]))
	tokens, err := LoadTokens(s.dir)
	if err != nil {
		return false, err
	}
	ok := false
	for _, t := range tokens {
		if subtle.ConstantTimeCompare(given, []byte(t.Hash)) == 1 {
			ok = true
		}
	}
	return ok, nil
}

func (s *Server) handleGetMeta(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	m := s.store.Meta()
	s.mu.Unlock()
	respondJSON(w, http.StatusOK, m)
}

func (s *Server) handlePutMeta(w http.ResponseWriter, r *http.Request) {
	var m Meta
	if !decodeBody(w, r, &m) {
		return
	}
	if m.Salt == "" || m.Check == "" {
		respondError(w, http.StatusBadRequest, "salt and check are required")
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if s.store.Meta().Salt != "" {
		respondError(w, http.StatusConflict, "meta is already set")
		return
	}
	if err := s.store.SetMeta(m); err != nil {
		log.Printf("sync: set meta: %v", err)
		respondError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	var req syncRequest
	if !decodeBody(w, r, &req) {
		return
	}
	if err := validatePush(req.Push); err != nil {
		respondError(w, http.StatusBadRequest, err.Error())
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	res := syncResponse{Accepted: []accepted{}, Conflicts: []string{}}
	next := s.store.Rev()
	var batch []Version
	for _, it := range req.Push {
		cur, ok := s.store.Get(it.ID)
		var curRev int64
		if ok {
			curRev = cur.Rev
		}
		if it.BaseRev != curRev {
			res.Conflicts = append(res.Conflicts, it.ID)
			continue
		}
		next++
		batch = append(batch, Version{ID: it.ID, Rev: next, Deleted: it.Deleted, Blob: it.Blob})
		res.Accepted = append(res.Accepted, accepted{ID: it.ID, Rev: next})
	}
	if len(batch) > 0 {
		if err := s.store.Append(batch); err != nil {
			log.Printf("sync: append: %v", err)
			respondError(w, http.StatusInternalServerError, "internal error")
			return
		}
	}

	res.Rev = s.store.Rev()
	res.Changes = s.store.Since(req.Since)
	if res.Changes == nil {
		res.Changes = []Version{}
	}
	respondJSON(w, http.StatusOK, res)
}

// validatePush checks the items of a push before the store is touched. It
// clears the blob of a deleted item, so a tombstone never holds data.
func validatePush(items []pushItem) error {
	seen := make(map[string]bool, len(items))
	for i := range items {
		it := &items[i]
		if it.ID == "" || len(it.ID) > maxIDBytes {
			return fmt.Errorf("id must be 1 to %d bytes", maxIDBytes)
		}
		if seen[it.ID] {
			return fmt.Errorf("duplicate id %q in one push", it.ID)
		}
		seen[it.ID] = true
		if it.Deleted {
			it.Blob = ""
			continue
		}
		if it.Blob == "" {
			return fmt.Errorf("item %q needs a blob", it.ID)
		}
		if len(it.Blob) > maxBlobBytes {
			return fmt.Errorf("blob of item %q is over %d bytes", it.ID, maxBlobBytes)
		}
	}
	return nil
}

// decodeBody reads one JSON object from the request body. It writes a 400 and
// returns false when the body is not valid JSON for v.
func decodeBody(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		respondError(w, http.StatusBadRequest, "bad json: "+err.Error())
		return false
	}
	return true
}

func respondJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("sync: write response: %v", err)
	}
}

func respondError(w http.ResponseWriter, status int, msg string) {
	respondJSON(w, status, map[string]string{"error": msg})
}
