-- Data required by the retry-journey and fault/deadline dashboard views.
ALTER TABLE mock_interactions ADD COLUMN scenario_name TEXT;
ALTER TABLE mock_interactions ADD COLUMN scenario_state TEXT;
ALTER TABLE mock_interactions ADD COLUMN next_scenario_state TEXT;
ALTER TABLE mock_interactions ADD COLUMN configured_delay_ms INTEGER;
ALTER TABLE mock_interactions ADD COLUMN fault TEXT;
ALTER TABLE mock_interactions ADD COLUMN client_deadline_ms INTEGER;
