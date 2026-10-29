package stoveprofiler

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/grafana/pyroscope-go"
)

const (
	defaultUploadInterval = 10 * time.Second
	maxUploadInterval     = time.Hour
	maxIdentityBytes      = 256
)

// Config selects a Stove run and native Go profile types. CPU is the default.
// Block/mutex profiling also requires the application to configure runtime sampling.
type Config struct {
	ServerURL      string
	RunID          string
	ServiceName    string
	UploadInterval time.Duration
	ProfileTypes   []pyroscope.ProfileType
	Headers        map[string]string
	// HTTPClient may supply custom certificate trust or machine authentication.
	// Requests have a five-second total deadline and redirects are refused.
	HTTPClient *http.Client
	Logger     pyroscope.Logger
}

// StartFromEnv returns nil when no profiling URL is configured. Other required
// variables are STOVE_RUN_ID and STOVE_PROFILING_SERVICE_NAME. Optional settings:
// STOVE_PROFILING_INTERVAL_SECONDS (10) and STOVE_PROFILING_TYPES (cpu).
// A configure callback can add headers, an HTTP client, or a logger.
func StartFromEnv(configure ...func(*Config)) (*Profiler, error) {
	endpoint := os.Getenv("STOVE_PROFILING_SERVER_URL")
	if endpoint == "" {
		return nil, nil
	}
	cfg := Config{ServerURL: endpoint, RunID: os.Getenv("STOVE_RUN_ID"), ServiceName: os.Getenv("STOVE_PROFILING_SERVICE_NAME")}
	if v := os.Getenv("STOVE_PROFILING_INTERVAL_SECONDS"); v != "" {
		seconds, err := strconv.Atoi(v)
		if err != nil || seconds < 1 || seconds > 3600 {
			return nil, errors.New("STOVE_PROFILING_INTERVAL_SECONDS must be 1..3600")
		}
		cfg.UploadInterval = time.Duration(seconds) * time.Second
	}
	if v := os.Getenv("STOVE_PROFILING_TYPES"); v != "" {
		for _, typ := range strings.Split(v, ",") {
			cfg.ProfileTypes = append(cfg.ProfileTypes, pyroscope.ProfileType(strings.TrimSpace(typ)))
		}
	}
	for _, apply := range configure {
		if apply == nil {
			return nil, errors.New("profiling configure callback must not be nil")
		}
		apply(&cfg)
	}
	return Start(cfg)
}

func (cfg Config) normalized() (Config, error) {
	endpoint, err := url.Parse(cfg.ServerURL)
	if err != nil || endpoint == nil || (endpoint.Scheme != "http" && endpoint.Scheme != "https") || endpoint.Hostname() == "" || endpoint.User != nil || endpoint.ForceQuery || endpoint.RawQuery != "" || strings.Contains(cfg.ServerURL, "#") {
		return Config{}, errors.New("profiling ServerURL must be an HTTP(S) base URL without credentials, query or fragment")
	}
	if !validIdentity(cfg.RunID) || !validIdentity(cfg.ServiceName) {
		return Config{}, errors.New("profiling run and service must be printable ASCII values without surrounding whitespace, at most 256 bytes")
	}
	if _, ok := os.LookupEnv("PYROSCOPE_ADHOC_SERVER_ADDRESS"); ok {
		return Config{}, errors.New("PYROSCOPE_ADHOC_SERVER_ADDRESS would override the Stove destination; unset it")
	}
	if cfg.UploadInterval == 0 {
		cfg.UploadInterval = defaultUploadInterval
	}
	if cfg.UploadInterval < time.Second || cfg.UploadInterval > maxUploadInterval {
		return Config{}, errors.New("profiling interval must be 1s..1h")
	}
	if len(cfg.ProfileTypes) == 0 {
		cfg.ProfileTypes = []pyroscope.ProfileType{pyroscope.ProfileCPU}
	}
	// The SDK keeps this slice for its background collectors. Own a copy even
	// when CPU is already selected; caller changes must not race with collection.
	cfg.ProfileTypes = slices.Clone(cfg.ProfileTypes)
	for _, t := range cfg.ProfileTypes {
		switch t {
		case pyroscope.ProfileCPU, pyroscope.ProfileAllocObjects, pyroscope.ProfileAllocSpace, pyroscope.ProfileInuseObjects, pyroscope.ProfileInuseSpace, pyroscope.ProfileGoroutines, pyroscope.ProfileMutexCount, pyroscope.ProfileMutexDuration, pyroscope.ProfileBlockCount, pyroscope.ProfileBlockDuration:
		default:
			return Config{}, fmt.Errorf("unsupported Stove profile type %q", t)
		}
	}
	if !slices.Contains(cfg.ProfileTypes, pyroscope.ProfileCPU) {
		cfg.ProfileTypes = append(cfg.ProfileTypes, pyroscope.ProfileCPU)
	}
	headers := make(map[string]string, len(cfg.Headers)+3)
	for k, v := range cfg.Headers {
		if !validHeaderName(k) || !validHeaderValue(v) {
			return Config{}, errors.New("invalid profiling HTTP header name or value")
		}
		if strings.HasPrefix(strings.ToLower(k), "x-stove-") {
			return Config{}, errors.New("X-Stove-* headers are managed by Stove")
		}
		canonical := http.CanonicalHeaderKey(k)
		if _, exists := headers[canonical]; exists {
			return Config{}, errors.New("duplicate profiling HTTP header")
		}
		headers[canonical] = v
	}
	cfg.Headers = headers
	return cfg, nil
}

func validIdentity(value string) bool {
	if value == "" || strings.TrimSpace(value) != value || len(value) > maxIdentityBytes {
		return false
	}
	for i := range len(value) {
		if value[i] < ' ' || value[i] > '~' {
			return false
		}
	}
	return true
}

// HTTP field names are tokens (RFC 9110 section 5.1). Fail during setup,
// rather than dropping profiles later in a background upload worker.
func validHeaderName(name string) bool {
	if name == "" {
		return false
	}
	for i := range len(name) {
		c := name[i]
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("!#$%&'*+-.^_`|~", rune(c))) {
			return false
		}
	}
	return true
}

func validHeaderValue(value string) bool {
	for i := range len(value) {
		if value[i] == 127 || value[i] < ' ' && value[i] != '\t' {
			return false
		}
	}
	return true
}
