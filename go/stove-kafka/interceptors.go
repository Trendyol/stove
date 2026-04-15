package stovekafkago

import (
	"context"

	"github.com/IBM/sarama"
)

// ProducerInterceptor implements sarama.ProducerInterceptor.
// It forwards every sent message to the Stove observer via gRPC.
// When Bridge is nil (production mode), all methods are no-ops.
type ProducerInterceptor struct {
	Bridge *Bridge
}

// OnSend is called when a message is about to be sent to Kafka.
// It reports the message to the Stove observer for shouldBePublished assertions.
func (i *ProducerInterceptor) OnSend(msg *sarama.ProducerMessage) {
	_ = i.Bridge.ReportPublished(context.Background(), msg)
}

// ConsumerInterceptor implements sarama.ConsumerInterceptor.
// It forwards every consumed message to the Stove observer via gRPC,
// and pre-reports the commit (offset+1) since Sarama has no onCommit interceptor.
// When Bridge is nil (production mode), all methods are no-ops.
type ConsumerInterceptor struct {
	Bridge *Bridge
}

// OnConsume is called when a message is consumed from Kafka.
// It reports the consumed message and a pre-committed offset (offset+1) to the observer.
// This satisfies Stove's shouldBeConsumed which checks isCommitted(offset+1).
func (i *ConsumerInterceptor) OnConsume(msg *sarama.ConsumerMessage) {
	_ = i.Bridge.ReportConsumed(context.Background(), msg)
	_ = i.Bridge.ReportCommitted(context.Background(), msg.Topic, msg.Partition, msg.Offset+1)
}
