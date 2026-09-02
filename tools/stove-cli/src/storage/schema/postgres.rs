diesel::table! {
  use diesel::sql_types::{BigInt, Integer, Jsonb, Nullable, Text};

  runs (id) {
    id -> Text,
    app_name -> Text,
    started_at -> Text,
    ended_at -> Nullable<Text>,
    status -> Text,
    total_tests -> Integer,
    passed -> Integer,
    failed -> Integer,
    duration_ms -> Nullable<BigInt>,
    systems -> Text,
    stove_version -> Nullable<Text>,
    metadata -> Jsonb,
  }
}

diesel::table! {
  tests (run_id, id) {
    id -> Text,
    run_id -> Text,
    test_name -> Text,
    spec_name -> Text,
    test_path -> Text,
    started_at -> Text,
    ended_at -> Nullable<Text>,
    status -> Text,
    duration_ms -> Nullable<BigInt>,
    error -> Nullable<Text>,
  }
}

diesel::table! {
  entries (id) {
    id -> BigInt,
    run_id -> Text,
    test_id -> Text,
    timestamp -> Text,
    system -> Text,
    action -> Text,
    result -> Text,
    input -> Nullable<Text>,
    output -> Nullable<Text>,
    metadata -> Nullable<Text>,
    expected -> Nullable<Text>,
    actual -> Nullable<Text>,
    error -> Nullable<Text>,
    trace_id -> Nullable<Text>,
    assertion_id -> Text,
    correlation_key -> Text,
  }
}

diesel::table! {
  spans (id) {
    id -> BigInt,
    run_id -> Text,
    trace_id -> Text,
    span_id -> Text,
    parent_span_id -> Nullable<Text>,
    operation_name -> Text,
    service_name -> Text,
    start_time_nanos -> BigInt,
    end_time_nanos -> BigInt,
    status -> Text,
    attributes -> Nullable<Text>,
    exception_type -> Nullable<Text>,
    exception_message -> Nullable<Text>,
    exception_stack_trace -> Nullable<Text>,
  }
}

diesel::table! {
  snapshots (id) {
    id -> BigInt,
    run_id -> Text,
    test_id -> Text,
    system -> Text,
    state_json -> Text,
    summary -> Text,
    captured_at -> Nullable<Text>,
    trigger_kind -> Text,
  }
}

diesel::table! {
  mock_interactions (id) {
    id -> BigInt,
    run_id -> Text,
    test_id -> Nullable<Text>,
    timestamp -> Text,
    system -> Text,
    protocol -> Text,
    method -> Text,
    target -> Text,
    matched -> Bool,
    stub_id -> Nullable<Text>,
    attribution -> Text,
    request_body -> Nullable<Text>,
    request_body_truncated -> Bool,
    response_body -> Nullable<Text>,
    response_body_truncated -> Bool,
    status -> Text,
    latency_ms -> Nullable<BigInt>,
    near_misses -> Nullable<Text>,
    trace_id -> Nullable<Text>,
    scenario_name -> Nullable<Text>,
    scenario_state -> Nullable<Text>,
    next_scenario_state -> Nullable<Text>,
    configured_delay_ms -> Nullable<BigInt>,
    fault -> Nullable<Text>,
    client_deadline_ms -> Nullable<BigInt>,
  }
}

diesel::table! {
  mock_warnings (id) {
    id -> BigInt,
    run_id -> Text,
    test_id -> Nullable<Text>,
    timestamp -> Text,
    system -> Text,
    kind -> Text,
    message -> Text,
    stub_id -> Nullable<Text>,
    target -> Nullable<Text>,
  }
}

diesel::table! {
  dashboard_settings (setting_key) {
    setting_key -> Text,
    setting_value -> Text,
    updated_at -> Timestamptz,
  }
}

diesel::table! {
  live_events (id) {
    id -> BigInt,
    event_id -> Text,
    run_id -> Text,
    event_type -> Text,
    payload -> Jsonb,
    created_at -> Timestamptz,
  }
}

diesel::table! {
  dashboard_event_inbox (event_id) {
    event_id -> Text,
    run_id -> Text,
    sequence -> Nullable<BigInt>,
    live_event_id -> BigInt,
    created_at -> Timestamptz,
  }
}

diesel::allow_tables_to_appear_in_same_query!(
  runs,
  tests,
  entries,
  spans,
  snapshots,
  mock_interactions,
  mock_warnings,
  dashboard_settings,
  live_events,
  dashboard_event_inbox,
);
