// Package franz provides Stove Kafka bridge hooks for twmb/franz-go.
//
// Register the hook when creating a franz-go client:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//
//	client, _ := kgo.NewClient(
//	    kgo.SeedBrokers("localhost:9092"),
//	    kgo.WithHooks(&franz.Hook{Bridge: bridge}),
//	)
package franz

import (
	"context"

	"github.com/twmb/franz-go/pkg/kgo"

	stovekafka "github.com/trendyol/stove/go/stove-kafka"
)

// Hook implements franz-go's HookProduceRecordBuffered and HookFetchRecordBuffered.
// When Bridge is nil (production mode), all methods return immediately with zero overhead.
type Hook struct {
	Bridge *stovekafka.Bridge
}

// OnProduceRecordBuffered is called when a record is buffered for producing.
// It reports the message to the Stove observer for shouldBePublished assertions.
func (h *Hook) OnProduceRecordBuffered(r *kgo.Record) {
	if h.Bridge == nil {
		return
	}
	_ = h.Bridge.ReportPublished(context.Background(), &stovekafka.PublishedMessage{
		Topic:   r.Topic,
		Key:     string(r.Key),
		Value:   r.Value,
		Headers: recordHeaders(r.Headers),
	})
}

// OnFetchRecordBuffered is called when a consumed record is buffered.
// It reports the consumed message and pre-reports the commit (offset+1)
// to the Stove observer for shouldBeConsumed assertions.
func (h *Hook) OnFetchRecordBuffered(r *kgo.Record) {
	if h.Bridge == nil {
		return
	}
	_ = h.Bridge.ReportConsumed(context.Background(), &stovekafka.ConsumedMessage{
		Topic:     r.Topic,
		Key:       string(r.Key),
		Value:     r.Value,
		Partition: r.Partition,
		Offset:    r.Offset,
		Headers:   recordHeaders(r.Headers),
	})
	_ = h.Bridge.ReportCommitted(context.Background(), r.Topic, r.Partition, r.Offset+1)
}

func recordHeaders(headers []kgo.RecordHeader) map[string]string {
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[h.Key] = string(h.Value)
	}
	return m
}
