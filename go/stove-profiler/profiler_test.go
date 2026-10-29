package stoveprofiler

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestDisabledAndInvalidConfiguration(t *testing.T) {
	t.Setenv("STOVE_PROFILING_SERVER_URL", "")
	p, err := StartFromEnv(func(*Config) { t.Fatal("disabled profiling must not configure SDK") })
	if err != nil || p != nil {
		t.Fatalf("%v %v", p, err)
	}
	if err = p.Close(); err != nil {
		t.Fatal(err)
	}
	for _, cfg := range []Config{
		{ServerURL: "ftp://example.com", RunID: "run", ServiceName: "app"},
		{ServerURL: "https://example.com", ServiceName: "app"},
		{ServerURL: "https://example.com", RunID: "run", ServiceName: "app", Headers: map[string]string{"x-stove-run-id": "override"}},
	} {
		if p, err := Start(cfg); err == nil {
			p.Close()
			t.Fatal("invalid config accepted")
		}
	}
}

func TestRealSDKFlushesShortRunWithIdentityAndPrefix(t *testing.T) {
	var mu sync.Mutex
	var identities []string
	var profiles int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/observe/ingest" || r.URL.Query().Get("spyName") != "gospy" {
			t.Errorf("wrong route: %s", r.URL)
		}
		if r.Header.Get("X-Stove-Run-Id") != "run" || r.Header.Get("X-Stove-Service-Name") != "app" || r.Header.Get("Authorization") != "Bearer test" {
			t.Errorf("wrong headers: %v", r.Header)
		}
		reader, err := r.MultipartReader()
		if err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		for {
			part, err := reader.NextPart()
			if err == io.EOF {
				break
			}
			if err != nil {
				t.Error(err)
				break
			}
			data, _ := io.ReadAll(part)
			if part.FormName() == "profile" && len(data) > 0 {
				mu.Lock()
				profiles++
				identities = append(identities, r.Header.Get("X-Stove-Instance-Id"))
				mu.Unlock()
			}
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	p, err := Start(Config{ServerURL: server.URL + "/observe", RunID: "run", ServiceName: "app", UploadInterval: time.Hour, Headers: map[string]string{"Authorization": "Bearer test"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = Start(Config{ServerURL: server.URL, RunID: "other", ServiceName: "app"}); err == nil {
		t.Fatal("second profiler accepted")
	}
	deadline := time.Now().Add(250 * time.Millisecond)
	for time.Now().Before(deadline) {
	}
	var closers sync.WaitGroup
	for range 8 {
		closers.Go(func() {
			if err := p.Close(); err != nil {
				t.Error(err)
			}
		})
	}
	closers.Wait()
	mu.Lock()
	for _, id := range identities {
		if id != identities[0] {
			t.Error("unstable instance identity")
		}
	}
	mu.Unlock()
	// Closing an empty handle must not release another active session.
	p2, err := Start(Config{ServerURL: server.URL + "/observe", RunID: "run", ServiceName: "app", Headers: map[string]string{"Authorization": "Bearer test"}})
	if err != nil {
		t.Fatal("session ownership was not released", err)
	}
	var empty Profiler
	if err := empty.Close(); err != nil {
		t.Fatal(err)
	}
	if other, err := Start(Config{ServerURL: server.URL, RunID: "run", ServiceName: "app"}); err == nil {
		other.Close()
		t.Fatal("empty handle released active session")
	}
	p2.Close()
	mu.Lock()
	defer mu.Unlock()
	if profiles == 0 || identities[0] == "" {
		t.Fatal("short run was not flushed")
	}
	// Each session is stable, and a new session receives its own identity.
	if identities[len(identities)-1] == identities[0] {
		t.Fatal("session identity was reused")
	}
}

func TestRetryPolicy(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if string(body) != "profile" {
			t.Error("body not replayed")
		}
		if calls.Add(1) < 3 {
			w.WriteHeader(503)
			return
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	req, _ := http.NewRequest(http.MethodPost, server.URL, bytes.NewBufferString("profile"))
	resp, err := (retryClient{client: server.Client()}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if calls.Load() != 3 || resp.StatusCode != 200 {
		t.Fatal("retry failed")
	}
	calls.Store(0)
	reject := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.WriteHeader(400) }))
	defer reject.Close()
	req, _ = http.NewRequest(http.MethodPost, reject.URL, strings.NewReader("profile"))
	resp, err = (retryClient{client: reject.Client()}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if calls.Load() != 1 {
		t.Fatal("invalid uploads should not retry")
	}
}

func TestSDKDoesNotFollowRedirects(t *testing.T) {
	var calls atomic.Int32
	destination := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer destination.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, destination.URL, http.StatusTemporaryRedirect)
	}))
	defer redirect.Close()
	p, err := Start(Config{ServerURL: redirect.URL, RunID: "run", ServiceName: "app", UploadInterval: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(250 * time.Millisecond)
	p.Close()
	if calls.Load() != 0 {
		t.Fatal("redirect forwarded profiler headers")
	}
}

func TestRoundedFinalSnapshotWindowIsRepairedOnceAcrossRetries(t *testing.T) {
	started := time.Now().Add(-time.Second)
	var windows []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		windows = append(windows, r.URL.RawQuery)
		from, _ := strconv.ParseInt(r.URL.Query().Get("from"), 10, 64)
		until, _ := strconv.ParseInt(r.URL.Query().Get("until"), 10, 64)
		if from != started.UnixNano() || until <= from {
			t.Errorf("invalid final window %d..%d", from, until)
		}
		if len(windows) == 1 {
			w.WriteHeader(503)
		}
	}))
	defer server.Close()
	rounded := strconv.FormatInt(started.Truncate(time.Hour).UnixNano(), 10)
	req, _ := http.NewRequest(http.MethodPost, server.URL+"?spyName=gospy&from="+rounded+"&until="+rounded, strings.NewReader("snapshot"))
	resp, err := (retryClient{client: server.Client(), started: started}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if len(windows) != 2 || windows[0] != windows[1] {
		t.Fatal("retry window changed", windows)
	}
}
