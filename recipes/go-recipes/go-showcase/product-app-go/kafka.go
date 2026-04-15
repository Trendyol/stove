package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"log"

	stovekafka "github.com/trendyol/stove/go/stove-kafka"
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

// KafkaProducer abstracts message production across different Kafka client libraries.
type KafkaProducer interface {
	SendMessage(topic, key string, value []byte) error
	Close() error
}

// initKafka creates a producer and starts a consumer using the specified library.
// Supported libraries: "sarama" (default), "franz", "segmentio".
func initKafka(library, brokers string, db *sql.DB, bridge *stovekafka.Bridge) (KafkaProducer, func(), error) {
	if brokers == "" {
		return nil, func() {}, nil
	}

	log.Printf("Kafka initializing with library=%s brokers=%s", library, brokers)

	groupID := "go-showcase-" + library

	switch library {
	case "franz":
		return initFranzKafka(brokers, groupID, db, bridge)
	case "segmentio":
		return initSegmentioKafka(brokers, groupID, db, bridge)
	default:
		return initSaramaKafka(brokers, groupID, db, bridge)
	}
}

// handleProductUpdate is shared consumer logic for all Kafka libraries.
func handleProductUpdate(db *sql.DB, value []byte) {
	var event ProductUpdateEvent
	if err := json.Unmarshal(value, &event); err != nil {
		log.Printf("failed to unmarshal update event: %v", err)
		return
	}

	if err := updateProduct(context.Background(), db, event.ID, event.Name, event.Price); err != nil {
		log.Printf("failed to update product %s: %v", event.ID, err)
	}
}
