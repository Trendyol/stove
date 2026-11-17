CREATE TABLE run_environments (
  run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE,
  environment TEXT NOT NULL
);
