package stovekafkago

import (
	"context"
	"testing"

	"github.com/IBM/sarama"
)

func TestNilBridge_ReportPublished(t *testing.T) {
	var b *Bridge
	err := b.ReportPublished(context.Background(), &sarama.ProducerMessage{
		Topic: "test-topic",
		Key:   sarama.StringEncoder("key"),
		Value: sarama.StringEncoder("value"),
	})
	if err != nil {
		t.Fatalf("expected nil error from nil bridge, got %v", err)
	}
}

func TestNilBridge_ReportConsumed(t *testing.T) {
	var b *Bridge
	err := b.ReportConsumed(context.Background(), &sarama.ConsumerMessage{
		Topic: "test-topic",
		Value: []byte("value"),
	})
	if err != nil {
		t.Fatalf("expected nil error from nil bridge, got %v", err)
	}
}

func TestNilBridge_ReportCommitted(t *testing.T) {
	var b *Bridge
	err := b.ReportCommitted(context.Background(), "test-topic", 0, 1)
	if err != nil {
		t.Fatalf("expected nil error from nil bridge, got %v", err)
	}
}

func TestNilBridge_Close(t *testing.T) {
	var b *Bridge
	err := b.Close()
	if err != nil {
		t.Fatalf("expected nil error from nil bridge close, got %v", err)
	}
}

func TestNewBridge_EmptyPort(t *testing.T) {
	b, err := NewBridge("")
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if b != nil {
		t.Fatalf("expected nil bridge for empty port, got %+v", b)
	}
}

func TestNewBridgeFromEnv_Unset(t *testing.T) {
	t.Setenv("STOVE_KAFKA_BRIDGE_PORT", "")
	b, err := NewBridgeFromEnv()
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if b != nil {
		t.Fatalf("expected nil bridge when env unset, got %+v", b)
	}
}

func TestEncodeSaramaKey_Nil(t *testing.T) {
	key, err := encodeSaramaKey(nil)
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if key != "" {
		t.Fatalf("expected empty string, got %q", key)
	}
}

func TestEncodeSaramaKey_String(t *testing.T) {
	key, err := encodeSaramaKey(sarama.StringEncoder("my-key"))
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if key != "my-key" {
		t.Fatalf("expected %q, got %q", "my-key", key)
	}
}

func TestProducerHeaders(t *testing.T) {
	headers := []sarama.RecordHeader{
		{Key: []byte("h1"), Value: []byte("v1")},
		{Key: []byte("h2"), Value: []byte("v2")},
	}
	m := producerHeaders(headers)
	if len(m) != 2 {
		t.Fatalf("expected 2 headers, got %d", len(m))
	}
	if m["h1"] != "v1" || m["h2"] != "v2" {
		t.Fatalf("unexpected headers: %v", m)
	}
}

func TestProducerHeaders_Empty(t *testing.T) {
	m := producerHeaders(nil)
	if len(m) != 0 {
		t.Fatalf("expected 0 headers, got %d", len(m))
	}
}

func TestConsumerHeaders(t *testing.T) {
	headers := []*sarama.RecordHeader{
		{Key: []byte("h1"), Value: []byte("v1")},
		{Key: []byte("h2"), Value: []byte("v2")},
	}
	m := consumerHeaders(headers)
	if len(m) != 2 {
		t.Fatalf("expected 2 headers, got %d", len(m))
	}
	if m["h1"] != "v1" || m["h2"] != "v2" {
		t.Fatalf("unexpected headers: %v", m)
	}
}

func TestConsumerHeaders_Empty(t *testing.T) {
	m := consumerHeaders(nil)
	if len(m) != 0 {
		t.Fatalf("expected 0 headers, got %d", len(m))
	}
}
