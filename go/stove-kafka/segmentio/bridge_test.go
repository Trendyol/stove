package segmentio

import (
	"context"
	"testing"

	kafka "github.com/segmentio/kafka-go"
)

func TestReportWritten_NilBridge(t *testing.T) {
	ReportWritten(context.Background(), nil, kafka.Message{
		Topic: "test-topic",
		Key:   []byte("key"),
		Value: []byte("value"),
	})
}

func TestReportRead_NilBridge(t *testing.T) {
	ReportRead(context.Background(), nil, kafka.Message{
		Topic:     "test-topic",
		Partition: 0,
		Offset:    42,
		Key:       []byte("key"),
		Value:     []byte("value"),
	})
}

func TestMessageHeaders(t *testing.T) {
	headers := []kafka.Header{
		{Key: "h1", Value: []byte("v1")},
		{Key: "h2", Value: []byte("v2")},
	}
	m := messageHeaders(headers)
	if len(m) != 2 {
		t.Fatalf("expected 2 headers, got %d", len(m))
	}
	if m["h1"] != "v1" || m["h2"] != "v2" {
		t.Fatalf("unexpected headers: %v", m)
	}
}

func TestMessageHeaders_Empty(t *testing.T) {
	m := messageHeaders(nil)
	if len(m) != 0 {
		t.Fatalf("expected 0 headers, got %d", len(m))
	}
}

func TestToPublished(t *testing.T) {
	msg := kafka.Message{
		Topic:   "test-topic",
		Key:     []byte("key"),
		Value:   []byte("value"),
		Headers: []kafka.Header{{Key: "h1", Value: []byte("v1")}},
	}
	pub := toPublished(msg)
	if pub.Topic != "test-topic" || pub.Key != "key" || string(pub.Value) != "value" {
		t.Fatalf("unexpected published message: %+v", pub)
	}
	if pub.Headers["h1"] != "v1" {
		t.Fatalf("unexpected headers: %v", pub.Headers)
	}
}

func TestToConsumed(t *testing.T) {
	msg := kafka.Message{
		Topic:     "test-topic",
		Partition: 3,
		Offset:    99,
		Key:       []byte("key"),
		Value:     []byte("value"),
		Headers:   []kafka.Header{{Key: "h1", Value: []byte("v1")}},
	}
	con := toConsumed(msg)
	if con.Topic != "test-topic" || con.Partition != 3 || con.Offset != 99 {
		t.Fatalf("unexpected consumed message: %+v", con)
	}
	if con.Key != "key" || string(con.Value) != "value" {
		t.Fatalf("unexpected key/value: %+v", con)
	}
	if con.Headers["h1"] != "v1" {
		t.Fatalf("unexpected headers: %v", con.Headers)
	}
}
