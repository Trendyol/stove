package main

import (
	"context"
	"database/sql"
	"log"
	"strings"
	"time"

	stovekafka "github.com/trendyol/stove/go/stove-kafka"
	stovefranz "github.com/trendyol/stove/go/stove-kafka/franz"
	"github.com/twmb/franz-go/pkg/kgo"
)

type franzProducer struct {
	client *kgo.Client
}

func (p *franzProducer) SendMessage(topic, key string, value []byte) error {
	results := p.client.ProduceSync(context.Background(), &kgo.Record{
		Topic: topic,
		Key:   []byte(key),
		Value: value,
	})
	return results.FirstErr()
}

func (p *franzProducer) Close() error {
	p.client.Close()
	return nil
}

func initFranzKafka(brokers, groupID string, db *sql.DB, bridge *stovekafka.Bridge) (KafkaProducer, func(), error) {
	brokerList := strings.Split(brokers, ",")
	hook := &stovefranz.Hook{Bridge: bridge}

	// Separate producer client — no consumer group overhead
	producerClient, err := kgo.NewClient(
		kgo.SeedBrokers(brokerList...),
		kgo.AllowAutoTopicCreation(),
		kgo.WithHooks(hook),
	)
	if err != nil {
		return nil, nil, err
	}

	// Separate consumer client — consumer group coordination won't block produces
	consumerClient, err := kgo.NewClient(
		kgo.SeedBrokers(brokerList...),
		kgo.ConsumeTopics(topicProductUpdate),
		kgo.ConsumerGroup(groupID),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.AutoCommitInterval(100*time.Millisecond),
		kgo.AllowAutoTopicCreation(),
	)
	if err != nil {
		producerClient.Close()
		return nil, nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())

	go func() {
		for {
			fetches := consumerClient.PollFetches(ctx)
			if ctx.Err() != nil {
				return
			}
			fetches.EachRecord(func(r *kgo.Record) {
				if r.Topic == topicProductUpdate {
					if handleProductUpdate(db, r.Value) {
						reportConsumed(
							context.Background(),
							bridge,
							r.Topic,
							string(r.Key),
							r.Value,
							r.Partition,
							r.Offset,
							franzConsumedHeaders(r.Headers),
						)
					}
				}
			})
		}
	}()

	stop := func() {
		cancel()
		consumerClient.Close()
		producerClient.Close()
	}

	log.Printf("Kafka (franz-go) initialized")
	return &franzProducer{client: producerClient}, stop, nil
}

func franzConsumedHeaders(headers []kgo.RecordHeader) map[string]string {
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[h.Key] = string(h.Value)
	}
	return m
}
