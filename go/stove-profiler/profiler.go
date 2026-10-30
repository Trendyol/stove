// Package stoveprofiler connects a Go application's native profiles to its Stove test run.
// StartFromEnv is inactive unless STOVE_PROFILING_SERVER_URL is set.
package stoveprofiler

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/grafana/pyroscope-go"
)

// Profiler owns a single SDK session and must not be copied after first use.
// Close is idempotent and safe on nil or a zero value.
type Profiler struct {
	once sync.Once
	sdk  *pyroscope.Profiler
	err  error
}

var sessionActive atomic.Bool

// Start enables profiling explicitly. It never forces garbage collection.
// CPU is always included because the SDK requires its collector for final flushing.
func Start(cfg Config) (*Profiler, error) {
	cfg, err := cfg.normalized()
	if err != nil {
		return nil, err
	}
	id := make([]byte, 16)
	if _, err := rand.Read(id); err != nil {
		return nil, err
	}
	cfg.Headers["X-Stove-Run-Id"] = cfg.RunID
	cfg.Headers["X-Stove-Service-Name"] = cfg.ServiceName
	cfg.Headers["X-Stove-Instance-Id"] = hex.EncodeToString(id)
	client := http.Client{}
	if cfg.HTTPClient != nil {
		client = *cfg.HTTPClient
	}
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if cfg.Logger == nil {
		cfg.Logger = stdLogger{}
	}
	// Reserve ownership before calling the SDK, without holding a lock across
	// SDK initialization or user-provided logging callbacks.
	if !sessionActive.CompareAndSwap(false, true) {
		return nil, errors.New("a Stove profiler is already active in this process")
	}
	sdk, err := pyroscope.Start(pyroscope.Config{
		ApplicationName: cfg.ServiceName, ServerAddress: cfg.ServerURL,
		UploadRate: cfg.UploadInterval, ProfileTypes: cfg.ProfileTypes, DisableGCRuns: true,
		HTTPHeaders: cfg.Headers, HTTPClient: retryClient{client: &client, started: time.Now()}, Logger: cfg.Logger,
	})
	if err != nil {
		sessionActive.Store(false)
		return nil, err
	}
	return &Profiler{sdk: sdk}, nil
}

// Close flushes the current interval and waits for uploads before stopping the
// SDK. Delivery is best effort; upload failures are reported through Logger.
// Call during graceful shutdown, before main returns (os.Exit skips defers).
func (p *Profiler) Close() error {
	if p == nil || p.sdk == nil {
		return nil
	}
	p.once.Do(func() {
		p.sdk.Flush(true)
		p.err = p.sdk.Stop()
		sessionActive.Store(false)
	})
	return p.err
}

type stdLogger struct{}

func (stdLogger) Infof(f string, a ...interface{})  { log.Printf("stove profiler: "+f, a...) }
func (stdLogger) Errorf(f string, a ...interface{}) { log.Printf("stove profiler: "+f, a...) }
func (stdLogger) Debugf(string, ...interface{})     {}
