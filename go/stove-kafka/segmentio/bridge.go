// Package segmentio provides Stove Kafka bridge helpers for segmentio/kafka-go.
//
// kafka-go does not have interceptor interfaces. Call ReportWritten after
// Writer.WriteMessages and ReportRead after Reader.ReadMessage/FetchMessage:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//
//	// After producing
//	_ = writer.WriteMessages(ctx, msgs...)
//	segmentio.ReportWritten(ctx, bridge, msgs...)
//
//	// After consuming
//	msg, _ := reader.ReadMessage(ctx)
//	segmentio.ReportRead(ctx, bridge, msg)
package segmentio

import (
	"context"

	kafka "github.com/segmentio/kafka-go"

	stovekafka "github.com/trendyol/stove/go/stove-kafka"
)

// ReportWritten reports produced messages to the Stove bridge.
// Safe to call with nil bridge (no-op, zero overhead).
func ReportWritten(ctx context.Context, bridge *stovekafka.Bridge, msgs ...kafka.Message) {
	if bridge == nil {
		return
	}
	for _, msg := range msgs {
		_ = bridge.ReportPublished(ctx, toPublished(msg))
	}
}

// ReportRead reports a consumed message and pre-reports the commit (offset+1)
// to the Stove bridge.
// Safe to call with nil bridge (no-op, zero overhead).
func ReportRead(ctx context.Context, bridge *stovekafka.Bridge, msg kafka.Message) {
	if bridge == nil {
		return
	}
	_ = bridge.ReportConsumed(ctx, toConsumed(msg))
	_ = bridge.ReportCommitted(ctx, msg.Topic, int32(msg.Partition), msg.Offset+1)
}

func toPublished(msg kafka.Message) *stovekafka.PublishedMessage {
	return &stovekafka.PublishedMessage{
		Topic:   msg.Topic,
		Key:     string(msg.Key),
		Value:   msg.Value,
		Headers: messageHeaders(msg.Headers),
	}
}

func toConsumed(msg kafka.Message) *stovekafka.ConsumedMessage {
	return &stovekafka.ConsumedMessage{
		Topic:     msg.Topic,
		Key:       string(msg.Key),
		Value:     msg.Value,
		Partition: int32(msg.Partition),
		Offset:    msg.Offset,
		Headers:   messageHeaders(msg.Headers),
	}
}

func messageHeaders(headers []kafka.Header) map[string]string {
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[h.Key] = string(h.Value)
	}
	return m
}
