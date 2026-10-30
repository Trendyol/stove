package stoveprofiler

import (
	"strings"
	"testing"
	"time"

	"github.com/grafana/pyroscope-go"
)

func TestConfigurationOwnsMutableValues(t *testing.T) {
	original := Config{
		ServerURL: "https://stove.example/observe", RunID: "run", ServiceName: "app",
		ProfileTypes: []pyroscope.ProfileType{pyroscope.ProfileCPU},
		Headers:      map[string]string{"authorization": "Bearer token"},
	}
	cfg, err := original.normalized()
	if err != nil {
		t.Fatal(err)
	}
	original.ProfileTypes[0] = "invalid"
	original.Headers["authorization"] = "changed"
	if cfg.ProfileTypes[0] != pyroscope.ProfileCPU || cfg.Headers["Authorization"] != "Bearer token" {
		t.Fatal("configuration aliases caller-owned data")
	}
	if cfg.UploadInterval != 10*time.Second {
		t.Fatal("incorrect default interval")
	}

	original.ProfileTypes = []pyroscope.ProfileType{pyroscope.ProfileInuseSpace}
	cfg, err = original.normalized()
	if err != nil || len(cfg.ProfileTypes) != 2 || cfg.ProfileTypes[1] != pyroscope.ProfileCPU {
		t.Fatalf("CPU collector required for flushing: %+v, %v", cfg, err)
	}
}

func TestInvalidConfigurationFailsBeforeStartingSDK(t *testing.T) {
	for name, mutate := range map[string]func(*Config){
		"blank service":      func(c *Config) { c.ServiceName = " " },
		"unicode identity":   func(c *Config) { c.RunID = "run-😃" },
		"trimmed identity":   func(c *Config) { c.RunID = " run " },
		"control identity":   func(c *Config) { c.ServiceName = "app\x00" },
		"oversized identity": func(c *Config) { c.RunID = strings.Repeat("x", 257) },
		"short interval":     func(c *Config) { c.UploadInterval = time.Millisecond },
		"long interval":      func(c *Config) { c.UploadInterval = time.Hour + time.Second },
		"unknown type":       func(c *Config) { c.ProfileTypes = []pyroscope.ProfileType{"unknown"} },
		"header name":        func(c *Config) { c.Headers = map[string]string{"bad header": "value"} },
		"header injection":   func(c *Config) { c.Headers = map[string]string{"Authorization": "token\r\nx: y"} },
		"header ambiguity":   func(c *Config) { c.Headers = map[string]string{"Authorization": "one", "authorization": "two"} },
		"reserved header":    func(c *Config) { c.Headers = map[string]string{"X-STOVE-Instance-Id": "override"} },
		"credentials":        func(c *Config) { c.ServerURL = "https://user:pass@stove.example" },
		"query":              func(c *Config) { c.ServerURL = "https://stove.example?" },
		"fragment":           func(c *Config) { c.ServerURL = "https://stove.example#" },
	} {
		t.Run(name, func(t *testing.T) {
			cfg := Config{ServerURL: "https://stove.example", RunID: "run", ServiceName: "app"}
			mutate(&cfg)
			if _, err := cfg.normalized(); err == nil {
				t.Fatal("invalid configuration accepted")
			}
		})
	}
}

func TestEnvironmentValidation(t *testing.T) {
	t.Setenv("STOVE_PROFILING_SERVER_URL", "https://stove.example")
	t.Setenv("STOVE_RUN_ID", "run")
	t.Setenv("STOVE_PROFILING_SERVICE_NAME", "app")
	for _, interval := range []string{"0", "-1", "3601", "1.5", "10s"} {
		t.Setenv("STOVE_PROFILING_INTERVAL_SECONDS", interval)
		if _, err := StartFromEnv(); err == nil {
			t.Fatalf("accepted interval %s", interval)
		}
	}
	t.Setenv("STOVE_PROFILING_INTERVAL_SECONDS", "10")
	if _, err := StartFromEnv(nil); err == nil {
		t.Fatal("nil callback accepted")
	}
	t.Setenv("PYROSCOPE_ADHOC_SERVER_ADDRESS", "")
	if _, err := StartFromEnv(); err == nil {
		t.Fatal("SDK destination override accepted")
	}
}
