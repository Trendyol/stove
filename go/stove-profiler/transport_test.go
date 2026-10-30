package stoveprofiler

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type trackedBody struct {
	io.Reader
	closed bool
}

func (b *trackedBody) Close() error { b.closed = true; return nil }

func TestCancellationClosesRetryResponse(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	body := &trackedBody{Reader: strings.NewReader("busy")}
	calls := 0
	client := retryClient{client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		r.Body.Close()
		cancel()
		return &http.Response{StatusCode: http.StatusServiceUnavailable, Body: body, Header: make(http.Header)}, nil
	})}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "https://stove.example/ingest", strings.NewReader("profile"))
	_, err := client.Do(req)
	if !errors.Is(err, context.Canceled) || calls != 1 || !body.closed {
		t.Fatalf("cancellation did not release response: %v, calls=%d, closed=%v", err, calls, body.closed)
	}
}

func TestResponseRemainsReadableUntilClosed(t *testing.T) {
	for _, replayable := range []bool{true, false} {
		calls := 0
		var uploadContext context.Context
		client := retryClient{client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls++
			uploadContext = r.Context()
			r.Body.Close()
			status := http.StatusOK
			if !replayable {
				status = http.StatusServiceUnavailable
			}
			return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("response")), Header: make(http.Header)}, nil
		})}}
		req, _ := http.NewRequest(http.MethodPost, "https://stove.example/ingest", strings.NewReader("profile"))
		if !replayable {
			req.GetBody = nil
		}
		response, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(response.Body)
		if err != nil || string(data) != "response" || uploadContext.Err() != nil || calls != 1 {
			t.Fatal("response was lost or cancelled prematurely")
		}
		response.Body.Close()
		if !errors.Is(uploadContext.Err(), context.Canceled) {
			t.Fatal("response close did not release deadline")
		}
	}
}

func TestTransportFailureReplaysRequest(t *testing.T) {
	calls := 0
	client := retryClient{client: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		body, _ := io.ReadAll(r.Body)
		r.Body.Close()
		if string(body) != "profile" {
			t.Fatal("retry lost request body")
		}
		if calls == 1 {
			return nil, errors.New("connection reset")
		}
		return &http.Response{StatusCode: http.StatusOK, Body: http.NoBody, Header: make(http.Header)}, nil
	})}}
	req, _ := http.NewRequest(http.MethodPost, "https://stove.example/ingest", strings.NewReader("profile"))
	response, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if calls != 2 {
		t.Fatalf("got %d attempts", calls)
	}
}
