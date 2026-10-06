CREATE TABLE bff_login_attempts (
  namespace text NOT NULL,
  id text NOT NULL,
  payload jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (namespace, id)
);
CREATE INDEX bff_login_attempts_expiry ON bff_login_attempts (namespace, expires_at);

CREATE TABLE bff_sessions (
  namespace text NOT NULL,
  id text NOT NULL,
  version bigint NOT NULL,
  payload jsonb NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (namespace, id)
);
CREATE INDEX bff_sessions_expiry ON bff_sessions (namespace, expires_at);
