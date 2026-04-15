package stovekafkago

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
