CREATE TABLE dashboard_preferences (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    top_filter_key TEXT
);
INSERT INTO dashboard_preferences (id, top_filter_key) VALUES (1, NULL);
