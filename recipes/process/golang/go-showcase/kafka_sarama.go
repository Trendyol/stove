package main

import (
	"context"
	"database/sql"
	"log"
	"strings"
	"time"

	"github.com/IBM/sarama"
	stovekafka "github.com/trendyol/stove/go/stove-kafka"
	stovesarama "github.com/trendyol/stove/go/stove-kafka/sarama"
)

type saramaProducer struct {
	producer sarama.SyncProducer
}

func (p *saramaProducer) SendMessage(topic, key string, value []byte) error {
	_, _, err := p.producer.SendMessage(&sarama.ProducerMessage{
		Topic: topic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(value),
	})
	return err
}

func (p *saramaProducer) Close() error {
	return p.producer.Close()
}

func initSaramaKafka(brokers, groupID string, db *sql.DB, bridge *stovekafka.Bridge) (KafkaProducer, func(), error) {
	brokerList := strings.Split(brokers, ",")

	config := sarama.NewConfig()
	config.Producer.Return.Successes = true
	config.Consumer.Offsets.Initial = sarama.OffsetOldest
	config.Consumer.Offsets.AutoCommit.Interval = 100 * time.Millisecond

	config.Producer.Interceptors = []sarama.ProducerInterceptor{
		&stovesarama.ProducerInterceptor{Bridge: bridge},
	}
	config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
		&stovesarama.ConsumerInterceptor{Bridge: bridge},
	}

	producer, err := sarama.NewSyncProducer(brokerList, config)
	if err != nil {
		return nil, nil, err
	}

	consumerGroup, err := sarama.NewConsumerGroup(brokerList, groupID, config)
	if err != nil {
		producer.Close()
		return nil, nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	handler := &saramaUpdateHandler{db: db}

	go func() {
		for {
			if err := consumerGroup.Consume(ctx, []string{topicProductUpdate}, handler); err != nil {
				log.Printf("sarama consumer group error: %v", err)
			}
			if ctx.Err() != nil {
				return
			}
		}
	}()

	stop := func() {
		cancel()
		consumerGroup.Close()
		producer.Close()
	}

	log.Printf("Kafka (sarama) initialized")
	return &saramaProducer{producer: producer}, stop, nil
}

type saramaUpdateHandler struct {
	db *sql.DB
}

func (h *saramaUpdateHandler) Setup(_ sarama.ConsumerGroupSession) error   { return nil }
func (h *saramaUpdateHandler) Cleanup(_ sarama.ConsumerGroupSession) error { return nil }

func (h *saramaUpdateHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for msg := range claim.Messages() {
		handleProductUpdate(h.db, msg.Value)
		session.MarkMessage(msg, "")
	}
	return nil
}
