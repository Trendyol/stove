CREATE TABLE profiles (
 id TEXT PRIMARY KEY,
 run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
 service TEXT NOT NULL,
 instance TEXT NOT NULL,
 from_ms BIGINT NOT NULL,
 until_ms BIGINT NOT NULL,
 payload BYTEA NOT NULL,
 byte_size BIGINT NOT NULL
);
CREATE INDEX profiles_run_window ON profiles(run_id, until_ms, id);
