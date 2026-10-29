package stoveprofiler

import (
	"context"
	"io"
	"net/http"
	"strconv"
	"time"
)

const (
	uploadTimeout     = 5 * time.Second
	maxUploadAttempts = 3
)

type retryClient struct {
	client  *http.Client
	started time.Time
}

func (c retryClient) Do(req *http.Request) (*http.Response, error) {
	req = c.repairFinalWindow(req)
	ctx, cancel := context.WithTimeout(req.Context(), uploadTimeout)
	// Successful response bodies must remain readable until the SDK closes them.
	for attempt := 0; ; attempt++ {
		r := req.Clone(ctx)
		if attempt > 0 {
			body, err := req.GetBody()
			if err != nil {
				cancel()
				return nil, err
			}
			r.Body = body
		}
		response, err := c.client.Do(r)
		if !retryable(response, err) || attempt == maxUploadAttempts-1 || req.GetBody == nil {
			if err != nil {
				cancel()
				return nil, err
			}
			response.Body = &cancelBody{ReadCloser: response.Body, cancel: cancel}
			return response, nil
		}
		if response != nil {
			_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
			_ = response.Body.Close()
		}
		timer := time.NewTimer(time.Duration(attempt+1) * 100 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			cancel()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
}

type cancelBody struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (b *cancelBody) Close() error { defer b.cancel(); return b.ReadCloser.Close() }

func (c retryClient) repairFinalWindow(req *http.Request) *http.Request {
	// pyroscope-go 1.4.3 truncates the final snapshot's end to UploadRate.
	// A partial interval can therefore end at/before its start. Repair that
	// invalid window once before retries; keep ordinary SDK windows untouched.
	if !c.started.IsZero() && req.URL.Query().Get("spyName") == "gospy" {
		query := req.URL.Query()
		from, fromErr := strconv.ParseInt(query.Get("from"), 10, 64)
		until, untilErr := strconv.ParseInt(query.Get("until"), 10, 64)
		if fromErr == nil && untilErr == nil && until <= from {
			req = req.Clone(req.Context())
			query.Set("from", strconv.FormatInt(max(from, c.started.UnixNano()), 10))
			query.Set("until", strconv.FormatInt(time.Now().UnixNano(), 10))
			req.URL.RawQuery = query.Encode()
		}
	}
	return req
}

func retryable(response *http.Response, err error) bool {
	if err != nil {
		return true
	}
	switch response.StatusCode {
	case http.StatusTooManyRequests, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return true
	default:
		return false
	}
}
