package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

const testToken = "good-token"

// newTestServer starts a server on a real store in a temp dir. The dir holds
// one token, testToken.
func newTestServer(t *testing.T) (*httptest.Server, *Store) {
	t.Helper()
	dir := t.TempDir()
	sum := sha256.Sum256([]byte(testToken))
	data, err := json.Marshal([]Token{{Name: "test", Hash: hex.EncodeToString(sum[:])}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "tokens.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	srv := httptest.NewServer(NewServer(st, dir).Handler())
	t.Cleanup(srv.Close)
	return srv, st
}

// call sends one request and returns the status code and the body.
func call(t *testing.T, srv *httptest.Server, method, path, token, body string) (int, []byte) {
	t.Helper()
	req, err := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, out
}

// postSync sends one sync request with the test token and decodes the 200 response.
func postSync(t *testing.T, srv *httptest.Server, body string) syncResponse {
	t.Helper()
	status, data := call(t, srv, http.MethodPost, "/v1/sync", testToken, body)
	return decodeSync(t, status, data)
}

// decodeSync checks that a sync call returned 200 and decodes its body.
func decodeSync(t *testing.T, status int, data []byte) syncResponse {
	t.Helper()
	if status != http.StatusOK {
		t.Fatalf("sync: status %d, want 200: %s", status, data)
	}
	var res syncResponse
	if err := json.Unmarshal(data, &res); err != nil {
		t.Fatalf("sync: decode: %v", err)
	}
	return res
}

func TestAuthRejectsMissingOrBadToken(t *testing.T) {
	srv, _ := newTestServer(t)
	for _, token := range []string{"", "bad-token"} {
		status, data := call(t, srv, http.MethodGet, "/v1/meta", token, "")
		if status != http.StatusUnauthorized {
			t.Errorf("GET meta with token %q: status %d, want 401", token, status)
		}
		var body map[string]string
		if err := json.Unmarshal(data, &body); err != nil || body["error"] != "unauthorized" {
			t.Errorf("GET meta with token %q: body %s", token, data)
		}
	}
	if status, _ := call(t, srv, http.MethodPost, "/v1/sync", "", `{"since":0,"push":[]}`); status != http.StatusUnauthorized {
		t.Errorf("POST sync without token: status %d, want 401", status)
	}
}

func TestMetaRoundTrip(t *testing.T) {
	srv, _ := newTestServer(t)
	if status, _ := call(t, srv, http.MethodPut, "/v1/meta", testToken, `{"salt":"s1","check":"c1"}`); status != http.StatusNoContent {
		t.Fatalf("first PUT meta: status %d, want 204", status)
	}
	status, data := call(t, srv, http.MethodGet, "/v1/meta", testToken, "")
	if status != http.StatusOK {
		t.Fatalf("GET meta: status %d, want 200", status)
	}
	var m Meta
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	if m != (Meta{Salt: "s1", Check: "c1"}) {
		t.Errorf("GET meta = %+v, want the PUT values", m)
	}
	if status, _ := call(t, srv, http.MethodPut, "/v1/meta", testToken, `{"salt":"s2","check":"c2"}`); status != http.StatusConflict {
		t.Errorf("second PUT meta: status %d, want 409", status)
	}
}

func TestMetaRequiresFields(t *testing.T) {
	srv, _ := newTestServer(t)
	for _, body := range []string{`{"salt":"","check":"c"}`, `{"salt":"s","check":""}`} {
		if status, _ := call(t, srv, http.MethodPut, "/v1/meta", testToken, body); status != http.StatusBadRequest {
			t.Errorf("PUT meta %s: status %d, want 400", body, status)
		}
	}
}

func TestSyncPushNewIDs(t *testing.T) {
	srv, _ := newTestServer(t)
	body := `{"since":0,"push":[{"id":"a","baseRev":0,"blob":"QQ=="},{"id":"b","baseRev":0,"blob":"Qg=="}]}`
	status, data := call(t, srv, http.MethodPost, "/v1/sync", testToken, body)
	res := decodeSync(t, status, data)
	if !strings.Contains(string(data), `"conflicts":[]`) {
		t.Errorf("conflicts is not an empty array: %s", data)
	}
	if want := []accepted{{ID: "a", Rev: 1}, {ID: "b", Rev: 2}}; !reflect.DeepEqual(res.Accepted, want) {
		t.Errorf("accepted = %+v, want %+v", res.Accepted, want)
	}
	if res.Rev != 2 {
		t.Errorf("rev = %d, want 2", res.Rev)
	}
	if len(res.Changes) != 2 || res.Changes[0].Rev != 1 || res.Changes[1].Rev != 2 {
		t.Errorf("changes = %+v, want revs 1 and 2", res.Changes)
	}
}

func TestSyncStaleBaseRevConflicts(t *testing.T) {
	srv, st := newTestServer(t)
	push := `{"since":0,"push":[{"id":"a","baseRev":0,"blob":"QQ=="}]}`
	postSync(t, srv, push)

	status, data := call(t, srv, http.MethodPost, "/v1/sync", testToken, push)
	res := decodeSync(t, status, data)
	if !reflect.DeepEqual(res.Conflicts, []string{"a"}) {
		t.Errorf("conflicts = %v, want [a]", res.Conflicts)
	}
	if len(res.Accepted) != 0 {
		t.Errorf("accepted = %+v, want none", res.Accepted)
	}
	if res.Rev != 1 || st.Rev() != 1 {
		t.Errorf("rev = %d, store rev = %d, want 1 for both", res.Rev, st.Rev())
	}
}

func TestSyncSinceFilter(t *testing.T) {
	srv, _ := newTestServer(t)
	postSync(t, srv, `{"since":0,"push":[{"id":"a","baseRev":0,"blob":"QQ=="},{"id":"b","baseRev":0,"blob":"Qg=="}]}`)
	postSync(t, srv, `{"since":0,"push":[{"id":"c","baseRev":0,"blob":"Qw=="}]}`)

	// A second device that last synced at rev 2 gets only the later change.
	res := postSync(t, srv, `{"since":2,"push":[]}`)
	if len(res.Changes) != 1 || res.Changes[0].ID != "c" || res.Changes[0].Rev != 3 {
		t.Errorf("changes since 2 = %+v, want only c at rev 3", res.Changes)
	}
}

func TestSyncDeleteStoresTombstone(t *testing.T) {
	srv, st := newTestServer(t)
	// The blob sent with a deleted item is dropped by the server.
	body := `{"since":0,"push":[{"id":"x","baseRev":0,"deleted":true,"blob":"QQ=="}]}`
	res := postSync(t, srv, body)
	if len(res.Accepted) != 1 {
		t.Fatalf("accepted = %+v, want x", res.Accepted)
	}
	v, ok := st.Get("x")
	if !ok || !v.Deleted || v.Blob != "" {
		t.Errorf("stored x = %+v (found %v), want a tombstone with an empty blob", v, ok)
	}
}

func TestSyncOversizedBlobIsRejected(t *testing.T) {
	srv, st := newTestServer(t)
	body := `{"since":0,"push":[{"id":"big","baseRev":0,"blob":"` + strings.Repeat("A", maxBlobBytes+1) + `"}]}`
	if status, _ := call(t, srv, http.MethodPost, "/v1/sync", testToken, body); status != http.StatusBadRequest {
		t.Errorf("oversized blob: status %d, want 400", status)
	}
	if _, ok := st.Get("big"); ok {
		t.Error("oversized blob was stored")
	}
}
