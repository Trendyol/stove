// Package stovekafka provides a Kafka message bridge for Stove e2e testing.
//
// It forwards produced/consumed Kafka messages via gRPC to Stove's
// StoveKafkaObserverGrpcServer, enabling shouldBeConsumed and
// shouldBePublished assertions in Kotlin tests.
//
// The core bridge is library-agnostic. Use the appropriate subpackage
// for your Kafka client:
//
//   - sarama  — IBM/sarama interceptors
//   - franz   — twmb/franz-go hooks
//   - segmentio — segmentio/kafka-go helpers
//
// Example with IBM/sarama:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//	config.Producer.Interceptors = []sarama.ProducerInterceptor{
//	    &stovesarama.ProducerInterceptor{Bridge: bridge},
//	}
//
// Example with franz-go:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//	client, _ := kgo.NewClient(kgo.WithHooks(&franz.Hook{Bridge: bridge}))
//
// Example with kafka-go:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//	_ = writer.WriteMessages(ctx, msgs...)
//	segmentio.ReportWritten(ctx, bridge, msgs...)
package stovekafka

import (
	"context"
	"fmt"
	"log"
	"os"

	"github.com/google/uuid"
	"github.com/trendyol/stove/go/stove-kafka/stoveobserver"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// PublishedMessage is a library-agnostic representation of a produced Kafka message.
type PublishedMessage struct {
	Topic   string
	Key     string
	Value   []byte
	Headers map[string]string
}

// ConsumedMessage is a library-agnostic representation of a consumed Kafka message.
type ConsumedMessage struct {
	Topic     string
	Key       string
	Value     []byte
	Partition int32
	Offset    int64
	Headers   map[string]string
}

const envBridgePort = "STOVE_KAFKA_BRIDGE_PORT"

// Bridge wraps the gRPC client to the Stove Kafka observer server.
// A nil Bridge is safe to use — all methods are no-ops.
type Bridge struct {
	client stoveobserver.StoveKafkaObserverServiceClient
	conn   *grpc.ClientConn
}

// NewBridge connects to the Stove observer on the given port.
// Returns (nil, nil) if port is empty (production mode — zero overhead).
func NewBridge(port string) (*Bridge, error) {
	if port == "" {
		return nil, nil
	}

	target := fmt.Sprintf("localhost:%s", port)
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("stove bridge: failed to connect to %s: %w", target, err)
	}

	log.Printf("Stove Kafka bridge connected to %s", target)
	return &Bridge{
		client: stoveobserver.NewStoveKafkaObserverServiceClient(conn),
		conn:   conn,
	}, nil
}

// NewBridgeFromEnv reads the STOVE_KAFKA_BRIDGE_PORT environment variable.
// Returns (nil, nil) if not set (production mode).
func NewBridgeFromEnv() (*Bridge, error) {
	return NewBridge(os.Getenv(envBridgePort))
}

// Close shuts down the gRPC connection. Safe to call on nil Bridge.
func (b *Bridge) Close() error {
	if b == nil {
		return nil
	}
	return b.conn.Close()
}

// ReportPublished sends a published message to the Stove observer.
// Safe to call on nil Bridge (no-op).
func (b *Bridge) ReportPublished(ctx context.Context, msg *PublishedMessage) error {
	if b == nil {
		return nil
	}

	_, err := b.client.OnPublishedMessage(ctx, &stoveobserver.PublishedMessage{
		Id:      uuid.New().String(),
		Message: msg.Value,
		Topic:   msg.Topic,
		Key:     msg.Key,
		Headers: msg.Headers,
	})
	if err != nil {
		log.Printf("stove bridge: failed to report published message: %v", err)
	}
	return err
}

// ReportConsumed sends a consumed message to the Stove observer.
// Safe to call on nil Bridge (no-op).
func (b *Bridge) ReportConsumed(ctx context.Context, msg *ConsumedMessage) error {
	if b == nil {
		return nil
	}

	_, err := b.client.OnConsumedMessage(ctx, &stoveobserver.ConsumedMessage{
		Id:        uuid.New().String(),
		Message:   msg.Value,
		Topic:     msg.Topic,
		Partition: msg.Partition,
		Offset:    msg.Offset,
		Key:       msg.Key,
		Headers:   msg.Headers,
	})
	if err != nil {
		log.Printf("stove bridge: failed to report consumed message: %v", err)
	}
	return err
}

// ReportCommitted sends a committed offset to the Stove observer.
// Safe to call on nil Bridge (no-op).
func (b *Bridge) ReportCommitted(ctx context.Context, topic string, partition int32, offset int64) error {
	if b == nil {
		return nil
	}

	_, err := b.client.OnCommittedMessage(ctx, &stoveobserver.CommittedMessage{
		Id:        uuid.New().String(),
		Topic:     topic,
		Partition: partition,
		Offset:    offset,
	})
	if err != nil {
		log.Printf("stove bridge: failed to report committed message: %v", err)
	}
	return err
}
