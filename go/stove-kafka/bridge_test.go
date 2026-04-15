package stovekafka

import (
	"context"
	"testing"
)

func TestNilBridge_ReportPublished(t *testing.T) {
	var b *Bridge
	err := b.ReportPublished(context.Background(), &PublishedMessage{
		Topic: "test-topic",
		Key:   "key",
		Value: []byte("value"),
	})
	if err != nil {
		t.Fatalf("expected nil error from nil bridge, got %v", err)
	}
}

func TestNilBridge_ReportConsumed(t *testing.T) {
	var b *Bridge
	err := b.ReportConsumed(context.Background(), &ConsumedMessage{
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
