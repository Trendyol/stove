use stove::storage::repository::Repository;

pub fn assert_pruning_contract(repo: &Repository) {
  let fixtures: serde_json::Value =
    serde_json::from_str(include_str!("../fixtures/retention.json")).unwrap();
  for case in fixtures["valid"].as_array().unwrap() {
    let runs = case["runs"].as_array().unwrap();
    if case.get("protected_run").is_some() || runs.iter().any(|r| r["status"] == "ABORTED") {
      continue;
    }
    repo.clear_all().unwrap();
    repo
      .update_retention_policy(&super::retention_support::equal_retention(0))
      .unwrap();
    for (index, run) in runs.iter().enumerate().rev() {
      let id = run["id"].as_str().unwrap();
      repo
        .save_run_start_with_metadata(
          id,
          run["app_name"].as_str().unwrap(),
          &format!("2024-01-01T00:00:{:02}Z", runs.len() - index),
          None,
          &[],
          &serde_json::from_value(run["metadata"].clone()).unwrap(),
        )
        .unwrap();
      if run["status"] != "RUNNING" {
        let failed = run["status"] != "PASSED";
        repo
          .save_run_end(
            id,
            "2024-01-01T00:01:00Z",
            1,
            i32::from(!failed),
            i32::from(failed),
            1000,
          )
          .unwrap();
      }
    }
    let policy = serde_json::from_value(case["policy"].clone()).unwrap();
    repo.update_retention_policy(&policy).unwrap();
    for run in runs {
      let id = run["id"].as_str().unwrap();
      let expected = !case["expired"]
        .as_array()
        .unwrap()
        .iter()
        .any(|expired| expired == id);
      assert_eq!(
        repo.get_run(id).unwrap().is_some(),
        expected,
        "{}: {id}",
        case["name"]
      );
    }
  }
}
