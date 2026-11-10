pub fn equal_retention(limit: usize) -> stove::storage::repository::RetentionPolicy {
  stove::storage::repository::RetentionPolicy {
    passed_runs_per_app: limit,
    failed_runs_per_app: limit,
    rules: Vec::new(),
  }
}
