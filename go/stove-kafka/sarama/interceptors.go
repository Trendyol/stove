// Package sarama provides Stove Kafka bridge interceptors for IBM/sarama.
//
// Wire the interceptors into your sarama.Config:
//
//	bridge, _ := stovekafka.NewBridgeFromEnv()
//
//	config := sarama.NewConfig()
//	config.Producer.Interceptors = []sarama.ProducerInterceptor{
//	    &stovesarama.ProducerInterceptor{Bridge: bridge},
//	}
//	config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
//	    &stovesarama.ConsumerInterceptor{Bridge: bridge},
//	}
package sarama

import (
	"context"

	"github.com/IBM/sarama"
	stovekafka "github.com/trendyol/stove/go/stove-kafka"
)

// ProducerInterceptor implements sarama.ProducerInterceptor.
// It forwards every sent message to the Stove observer via gRPC.
// When Bridge is nil (production mode), all methods are no-ops.
type ProducerInterceptor struct {
	Bridge *stovekafka.Bridge
}

// OnSend is called when a message is about to be sent to Kafka.
// It reports the message to the Stove observer for shouldBePublished assertions.
func (i *ProducerInterceptor) OnSend(msg *sarama.ProducerMessage) {
	value, err := msg.Value.Encode()
	if err != nil {
		return
	}

	key, _ := encodeSaramaKey(msg.Key)

	_ = i.Bridge.ReportPublished(context.Background(), &stovekafka.PublishedMessage{
		Topic:   msg.Topic,
		Key:     key,
		Value:   value,
		Headers: producerHeaders(msg.Headers),
	})
}

// ConsumerInterceptor implements sarama.ConsumerInterceptor.
// It forwards every consumed message to the Stove observer via gRPC,
// and pre-reports the commit (offset+1) since Sarama has no onCommit interceptor.
// When Bridge is nil (production mode), all methods are no-ops.
type ConsumerInterceptor struct {
	Bridge *stovekafka.Bridge
}

// OnConsume is called when a message is consumed from Kafka.
// It reports the consumed message and a pre-committed offset (offset+1) to the observer.
// This satisfies Stove's shouldBeConsumed which checks isCommitted(offset+1).
func (i *ConsumerInterceptor) OnConsume(msg *sarama.ConsumerMessage) {
	_ = i.Bridge.ReportConsumed(context.Background(), &stovekafka.ConsumedMessage{
		Topic:     msg.Topic,
		Key:       string(msg.Key),
		Value:     msg.Value,
		Partition: msg.Partition,
		Offset:    msg.Offset,
		Headers:   consumerHeaders(msg.Headers),
	})
	_ = i.Bridge.ReportCommitted(context.Background(), msg.Topic, msg.Partition, msg.Offset+1)
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
