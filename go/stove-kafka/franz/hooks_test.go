package franz

import (
	"testing"

	"github.com/twmb/franz-go/pkg/kgo"
)

func TestHook_NilBridge_OnProduceRecordBuffered(t *testing.T) {
	h := &Hook{Bridge: nil}
	h.OnProduceRecordBuffered(&kgo.Record{
		Topic: "test-topic",
		Key:   []byte("key"),
		Value: []byte("value"),
	})
}

func TestHook_NilBridge_OnFetchRecordBuffered(t *testing.T) {
	h := &Hook{Bridge: nil}
	h.OnFetchRecordBuffered(&kgo.Record{
		Topic:     "test-topic",
		Partition: 0,
		Offset:    42,
		Key:       []byte("key"),
		Value:     []byte("value"),
	})
}

func TestRecordHeaders(t *testing.T) {
	headers := []kgo.RecordHeader{
		{Key: "h1", Value: []byte("v1")},
		{Key: "h2", Value: []byte("v2")},
	}
	m := recordHeaders(headers)
	if len(m) != 2 {
		t.Fatalf("expected 2 headers, got %d", len(m))
	}
	if m["h1"] != "v1" || m["h2"] != "v2" {
		t.Fatalf("unexpected headers: %v", m)
	}
}

func TestRecordHeaders_Empty(t *testing.T) {
	m := recordHeaders(nil)
	if len(m) != 0 {
		t.Fatalf("expected 0 headers, got %d", len(m))
	}
}
