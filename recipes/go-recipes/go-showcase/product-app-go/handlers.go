package main

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
)

// Product represents a product entity.
type Product struct {
	ID    string  `json:"id"`
	Name  string  `json:"name"`
	Price float64 `json:"price"`
}

type createProductRequest struct {
	Name  string  `json:"name"`
	Price float64 `json:"price"`
}

func registerRoutes(mux *http.ServeMux, db *sql.DB, producer sarama.SyncProducer) {
	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("POST /api/products", handleCreateProduct(db, producer))
	mux.HandleFunc("GET /api/products/{id}", handleGetProduct(db))
	mux.HandleFunc("GET /api/products", handleListProducts(db))
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func handleCreateProduct(db *sql.DB, producer sarama.SyncProducer) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req createProductRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, `{"error":"invalid request body"}`, http.StatusBadRequest)
			return
		}

		product := Product{
			ID:    uuid.New().String(),
			Name:  req.Name,
			Price: req.Price,
		}

		if err := insertProduct(r.Context(), db, product); err != nil {
			http.Error(w, `{"error":"failed to create product"}`, http.StatusInternalServerError)
			return
		}

		// Publish ProductCreatedEvent to Kafka
		if producer != nil {
			event := ProductCreatedEvent{ID: product.ID, Name: product.Name, Price: product.Price}
			eventBytes, err := json.Marshal(event)
			if err != nil {
				log.Printf("failed to marshal ProductCreatedEvent: %v", err)
			} else if _, _, err := producer.SendMessage(&sarama.ProducerMessage{
				Topic: topicProductCreated,
				Key:   sarama.StringEncoder(product.ID),
				Value: sarama.ByteEncoder(eventBytes),
			}); err != nil {
				log.Printf("failed to publish ProductCreatedEvent: %v", err)
			}
		}

		writeJSON(w, http.StatusCreated, product)
	}
}

func handleGetProduct(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		product, err := getProduct(r.Context(), db, id)
		if err != nil {
			http.Error(w, `{"error":"internal error"}`, http.StatusInternalServerError)
			return
		}
		if product == nil {
			http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
			return
		}

		writeJSON(w, http.StatusOK, product)
	}
}

func handleListProducts(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		products, err := listProducts(r.Context(), db)
		if err != nil {
			http.Error(w, `{"error":"internal error"}`, http.StatusInternalServerError)
			return
		}
		if products == nil {
			products = []Product{}
		}

		writeJSON(w, http.StatusOK, products)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
