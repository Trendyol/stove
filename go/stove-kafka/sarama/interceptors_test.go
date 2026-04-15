package sarama

import (
	"testing"

	"github.com/IBM/sarama"
)

func TestProducerInterceptor_NilBridge(t *testing.T) {
	i := &ProducerInterceptor{Bridge: nil}
	// Should not panic
	i.OnSend(&sarama.ProducerMessage{
		Topic: "test-topic",
		Key:   sarama.StringEncoder("key"),
		Value: sarama.StringEncoder("value"),
	})
}

func TestConsumerInterceptor_NilBridge(t *testing.T) {
	i := &ConsumerInterceptor{Bridge: nil}
	// Should not panic
	i.OnConsume(&sarama.ConsumerMessage{
		Topic:     "test-topic",
		Partition: 0,
		Offset:    42,
		Value:     []byte("value"),
	})
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
