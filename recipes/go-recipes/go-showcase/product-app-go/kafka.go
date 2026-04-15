package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"log"
	"strings"

	"github.com/IBM/sarama"
	stovekafkago "github.com/trendyol/stove/go/stove-kafka"
)

const (
	topicProductCreated = "product.created"
	topicProductUpdate  = "product.update"
)

// ProductCreatedEvent is published to the product.created topic when a product is created.
type ProductCreatedEvent struct {
	ID    string  `json:"id"`
	Name  string  `json:"name"`
	Price float64 `json:"price"`
}

// ProductUpdateEvent is consumed from the product.update topic to update existing products.
type ProductUpdateEvent struct {
	ID    string  `json:"id"`
	Name  string  `json:"name"`
	Price float64 `json:"price"`
}

func initKafka(brokers string, db *sql.DB, bridge *stovekafkago.Bridge) (sarama.SyncProducer, func(), error) {
	if brokers == "" {
		return nil, func() {}, nil
	}

	brokerList := strings.Split(brokers, ",")

	config := sarama.NewConfig()
	config.Producer.Return.Successes = true
	config.Consumer.Offsets.Initial = sarama.OffsetOldest

	// Wire Stove bridge interceptors — no-ops when bridge is nil (production mode)
	config.Producer.Interceptors = []sarama.ProducerInterceptor{
		&stovekafkago.ProducerInterceptor{Bridge: bridge},
	}
	config.Consumer.Interceptors = []sarama.ConsumerInterceptor{
		&stovekafkago.ConsumerInterceptor{Bridge: bridge},
	}

	producer, err := sarama.NewSyncProducer(brokerList, config)
	if err != nil {
		return nil, nil, err
	}

	// Start consumer group for product.update topic
	consumerGroup, err := sarama.NewConsumerGroup(brokerList, "go-showcase-group", config)
	if err != nil {
		producer.Close()
		return nil, nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	handler := &productUpdateHandler{db: db}

	go func() {
		for {
			if err := consumerGroup.Consume(ctx, []string{topicProductUpdate}, handler); err != nil {
				log.Printf("consumer group error: %v", err)
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

	log.Printf("Kafka initialized with brokers: %s", brokers)
	return producer, stop, nil
}

// productUpdateHandler implements sarama.ConsumerGroupHandler.
type productUpdateHandler struct {
	db *sql.DB
}

func (h *productUpdateHandler) Setup(_ sarama.ConsumerGroupSession) error   { return nil }
func (h *productUpdateHandler) Cleanup(_ sarama.ConsumerGroupSession) error { return nil }

func (h *productUpdateHandler) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for msg := range claim.Messages() {
		var event ProductUpdateEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			log.Printf("failed to unmarshal update event: %v", err)
			session.MarkMessage(msg, "")
			continue
		}

		if err := updateProduct(context.Background(), h.db, event.ID, event.Name, event.Price); err != nil {
			log.Printf("failed to update product %s: %v", event.ID, err)
		}

		session.MarkMessage(msg, "")
	}
	return nil
}
