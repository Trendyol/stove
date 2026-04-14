package main

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/XSAM/otelsql"
	_ "github.com/lib/pq"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

func initDB(connStr string) (*sql.DB, error) {
	// otelsql wraps database/sql — all queries are automatically traced
	db, err := otelsql.Open("postgres", connStr,
		otelsql.WithAttributes(semconv.DBSystemPostgreSQL),
	)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping: %w", err)
	}
	return db, nil
}

func insertProduct(ctx context.Context, db *sql.DB, p Product) error {
	_, err := db.ExecContext(ctx,
		"INSERT INTO products (id, name, price) VALUES ($1, $2, $3)",
		p.ID, p.Name, p.Price,
	)
	return err
}

func getProduct(ctx context.Context, db *sql.DB, id string) (*Product, error) {
	row := db.QueryRowContext(ctx, "SELECT id, name, price FROM products WHERE id = $1", id)
	var p Product
	if err := row.Scan(&p.ID, &p.Name, &p.Price); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &p, nil
}

func listProducts(ctx context.Context, db *sql.DB) ([]Product, error) {
	rows, err := db.QueryContext(ctx, "SELECT id, name, price FROM products")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var products []Product
	for rows.Next() {
		var p Product
		if err := rows.Scan(&p.ID, &p.Name, &p.Price); err != nil {
			return nil, err
		}
		products = append(products, p)
	}
	return products, rows.Err()
}
