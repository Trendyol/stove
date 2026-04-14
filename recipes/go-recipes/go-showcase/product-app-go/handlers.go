package main

import (
	"database/sql"
	"encoding/json"
	"net/http"

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

func registerRoutes(mux *http.ServeMux, db *sql.DB) {
	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("POST /api/products", handleCreateProduct(db))
	mux.HandleFunc("GET /api/products/{id}", handleGetProduct(db))
	mux.HandleFunc("GET /api/products", handleListProducts(db))
}

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "UP"})
}

func handleCreateProduct(db *sql.DB) http.HandlerFunc {
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

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(product)
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

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(product)
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

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(products)
	}
}
