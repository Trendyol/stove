package main

import (
	"context"
	"database/sql"
	"errors"
	"log"
	"strings"
	"time"

	kafka "github.com/segmentio/kafka-go"
	stovekafka "github.com/trendyol/stove/go/stove-kafka"
	"github.com/trendyol/stove/go/stove-kafka/segmentio"
)

type segmentioProducer struct {
	writer *kafka.Writer
	bridge *stovekafka.Bridge
}

func (p *segmentioProducer) SendMessage(topic, key string, value []byte) error {
	ctx := context.Background()
	msg := kafka.Message{
		Topic: topic,
		Key:   []byte(key),
		Value: value,
	}
	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		return err
	}
	segmentio.ReportWritten(ctx, p.bridge, msg)
	return nil
}

func (p *segmentioProducer) Close() error {
	return p.writer.Close()
}

func initSegmentioKafka(brokers, groupID string, db *sql.DB, bridge *stovekafka.Bridge) (KafkaProducer, func(), error) {
	brokerList := strings.Split(brokers, ",")

	writer := &kafka.Writer{
		Addr:                   kafka.TCP(brokerList...),
		BatchSize:              1,
		BatchTimeout:           10 * time.Millisecond,
		RequiredAcks:           kafka.RequireAll,
		AllowAutoTopicCreation: true,
	}

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        brokerList,
		GroupID:        groupID,
		Topic:          topicProductUpdate,
		MinBytes:       1,
		MaxBytes:       10e6,
		CommitInterval: 100 * time.Millisecond,
		MaxWait:        500 * time.Millisecond,
	})

	ctx, cancel := context.WithCancel(context.Background())

	go func() {
		for {
			msg, err := reader.ReadMessage(ctx)
			if err != nil {
				if errors.Is(err, context.Canceled) {
					return
				}
				log.Printf("segmentio reader error: %v", err)
				continue
			}
			segmentio.ReportRead(ctx, bridge, msg)
			handleProductUpdate(db, msg.Value)
		}
	}()

	stop := func() {
		cancel()
		reader.Close()
		writer.Close()
	}

	log.Printf("Kafka (segmentio/kafka-go) initialized")
	return &segmentioProducer{writer: writer, bridge: bridge}, stop, nil
}
