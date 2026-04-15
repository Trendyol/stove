// Package stovekafkago provides a Kafka message bridge for Stove e2e testing.
//
// It intercepts Sarama producer/consumer messages and forwards them via gRPC
// to Stove's StoveKafkaObserverGrpcServer, enabling shouldBeConsumed and
// shouldBePublished assertions in Kotlin tests.
//
// Usage:
//
//	bridge, err := stovekafkago.NewBridgeFromEnv()
//	// bridge is nil if STOVE_KAFKA_BRIDGE_PORT is not set (production mode)
//
//	config := sarama.NewConfig()
//	config.Producer.Interceptors = []sarama.ProducerInterceptor{
//	    &stovekafkago.ProducerInterceptor{Bridge: bridge},
//	}
//	config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
//	    &stovekafkago.ConsumerInterceptor{Bridge: bridge},
//	}
package stovekafkago

import (
	"context"
	"fmt"
	"log"
	"os"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"github.com/trendyol/stove/go/stove-kafka/stoveobserver"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

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
func (b *Bridge) ReportPublished(ctx context.Context, msg *sarama.ProducerMessage) error {
	if b == nil {
		return nil
	}

	value, err := msg.Value.Encode()
	if err != nil {
		return fmt.Errorf("stove bridge: failed to encode producer message value: %w", err)
	}

	key, err := encodeSaramaKey(msg.Key)
	if err != nil {
		return fmt.Errorf("stove bridge: failed to encode producer message key: %w", err)
	}

	_, err = b.client.OnPublishedMessage(ctx, &stoveobserver.PublishedMessage{
		Id:      uuid.New().String(),
		Message: value,
		Topic:   msg.Topic,
		Key:     key,
		Headers: producerHeaders(msg.Headers),
	})
	if err != nil {
		log.Printf("stove bridge: failed to report published message: %v", err)
	}
	return err
}

// ReportConsumed sends a consumed message to the Stove observer.
// Safe to call on nil Bridge (no-op).
func (b *Bridge) ReportConsumed(ctx context.Context, msg *sarama.ConsumerMessage) error {
	if b == nil {
		return nil
	}

	_, err := b.client.OnConsumedMessage(ctx, &stoveobserver.ConsumedMessage{
		Id:        uuid.New().String(),
		Message:   msg.Value,
		Topic:     msg.Topic,
		Partition: msg.Partition,
		Offset:    msg.Offset,
		Key:       string(msg.Key),
		Headers:   consumerHeaders(msg.Headers),
	})
	if err != nil {
		log.Printf("stove bridge: failed to report consumed message: %v", err)
	}
	return err
}

func encodeSaramaKey(key sarama.Encoder) (string, error) {
	if key == nil {
		return "", nil
	}
	keyBytes, err := key.Encode()
	if err != nil {
		return "", err
	}
	return string(keyBytes), nil
}

func producerHeaders(headers []sarama.RecordHeader) map[string]string {
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[string(h.Key)] = string(h.Value)
	}
	return m
}

func consumerHeaders(headers []*sarama.RecordHeader) map[string]string {
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[string(h.Key)] = string(h.Value)
	}
	return m
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
