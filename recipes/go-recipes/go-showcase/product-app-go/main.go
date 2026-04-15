package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	stovekafka "github.com/trendyol/stove/go/stove-kafka"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	ctx := context.Background()

	port := getEnv("APP_PORT", "8080")
	dbHost := getEnv("DB_HOST", "localhost")
	dbPort := getEnv("DB_PORT", "5432")
	dbName := getEnv("DB_NAME", "stove")
	dbUser := getEnv("DB_USER", "sa")
	dbPass := getEnv("DB_PASS", "sa")

	shutdownTracing, err := initTracing(ctx, "go-showcase")
	if err != nil {
		log.Fatalf("failed to init tracing: %v", err)
	}
	defer shutdownTracing(ctx)

	connStr := fmt.Sprintf(
		"host=%s port=%s dbname=%s user=%s password=%s sslmode=disable",
		dbHost, dbPort, dbName, dbUser, dbPass,
	)

	db, err := initDB(connStr)
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}
	defer db.Close()

	// Initialize Stove Kafka bridge (nil in production — zero overhead)
	bridge, err := stovekafka.NewBridgeFromEnv()
	if err != nil {
		log.Fatalf("failed to init stove bridge: %v", err)
	}
	defer bridge.Close()

	// Initialize Kafka producer and consumer
	kafkaLibrary := getEnv("KAFKA_LIBRARY", "sarama")
	brokers := getEnv("KAFKA_BROKERS", "")
	producer, stopKafka, err := initKafka(kafkaLibrary, brokers, db, bridge)
	if err != nil {
		log.Fatalf("failed to init kafka: %v", err)
	}
	defer stopKafka()

	mux := http.NewServeMux()
	registerRoutes(mux, db, producer)

	// Wrap with OTel HTTP instrumentation for automatic span creation
	handler := otelhttp.NewHandler(mux, "http.request")

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// Graceful shutdown on SIGTERM/SIGINT
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)

	go func() {
		log.Printf("Go showcase app listening on :%s", port)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-stop
	log.Println("shutting down...")

	shutdownCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Fatalf("shutdown error: %v", err)
	}
	log.Println("server stopped")
}
