#[path = "common/retention_contract.rs"]
mod retention_contract;
#[path = "common/retention.rs"]
mod retention_support;
use retention_support::equal_retention;
use std::collections::BTreeMap;
use stove::storage::repository::{Repository, RetentionPolicy, RetentionRule, RunFilter};

fn seed(repo: &Repository, id: &str, app: &str, failed: bool, metadata: &[(&str, &str)]) {
  repo
    .save_run_start_with_metadata(
      id,
      app,
      "2024-01-01T00:00:00Z",
      None,
      &[],
      &metadata
        .iter()
        .map(|(k, v)| ((*k).into(), (*v).into()))
        .collect(),
    )
    .unwrap();
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

fn ids(repo: &Repository, app: &str) -> Vec<String> {
  let mut ids = repo
    .get_run_page(
      &RunFilter {
        app: Some(app.into()),
        ..Default::default()
      },
      100,
      None,
    )
    .unwrap()
    .runs
    .into_iter()
    .map(|run| run.id)
    .collect::<Vec<_>>();
  ids.sort();
  ids
}

#[test]
fn default_quotas_preserve_failures_when_an_app_turns_green() {
  let repo = Repository::connect_sqlite(":memory:", &RetentionPolicy::default()).unwrap();
  for i in 0..8 {
    seed(&repo, &format!("failed-{i}"), "app", true, &[]);
  }
  for i in 0..10 {
    seed(&repo, &format!("passed-{i}"), "app", false, &[]);
  }
  repo
    .save_run_start("active", "app", "2023-01-01T00:00:00Z", &[])
    .unwrap();
  assert_eq!(
    ids(&repo, "app"),
    [
      "active", "failed-3", "failed-4", "failed-5", "failed-6", "failed-7", "passed-7", "passed-8",
      "passed-9"
    ]
  );
}

#[test]
fn rules_have_separate_buckets_and_first_match_inherits_unspecified_limit() {
  let repo = Repository::connect_sqlite(":memory:", &equal_retention(0)).unwrap();
  let policy = RetentionPolicy {
    passed_runs_per_app: 1,
    failed_runs_per_app: 2,
    rules: vec![
      RetentionRule {
        app_name: Some("app".into()),
        metadata: BTreeMap::from([
          ("team".into(), vec!["checkout".into(), "payments".into()]),
          ("branch".into(), vec!["main".into()]),
        ]),
        passed_runs_per_app: None,
        failed_runs_per_app: Some(4),
      },
      RetentionRule {
        app_name: None,
        metadata: BTreeMap::from([("team".into(), vec!["checkout".into()])]),
        passed_runs_per_app: Some(0),
        failed_runs_per_app: Some(1),
      },
    ],
  };
  for i in 0..6 {
    for failed in [false, true] {
      let outcome = if failed { "f" } else { "p" };
      seed(
        &repo,
        &format!("main-{outcome}-{i}"),
        "app",
        failed,
        &[("team", "checkout"), ("branch", "main")],
      );
      seed(
        &repo,
        &format!("dev-{outcome}-{i}"),
        "app",
        failed,
        &[("team", "checkout"), ("branch", "dev")],
      );
      seed(
        &repo,
        &format!("other-{outcome}-{i}"),
        "app",
        failed,
        &[("team", "catalog")],
      );
      seed(
        &repo,
        &format!("second-{outcome}-{i}"),
        "second",
        failed,
        &[("team", "checkout"), ("branch", "main")],
      );
    }
  }
  repo.update_retention_policy(&policy).unwrap();
  assert_eq!(
    ids(&repo, "app"),
    [
      "dev-f-5",
      "dev-p-0",
      "dev-p-1",
      "dev-p-2",
      "dev-p-3",
      "dev-p-4",
      "dev-p-5",
      "main-f-2",
      "main-f-3",
      "main-f-4",
      "main-f-5",
      "main-p-5",
      "other-f-4",
      "other-f-5",
      "other-p-5"
    ]
  );
  assert_eq!(ids(&repo, "second").len(), 7);
  // Another accepted value joins the same first-rule bucket, not the default.
  seed(
    &repo,
    "payments-f",
    "app",
    true,
    &[("team", "payments"), ("branch", "main")],
  );
  assert!(!ids(&repo, "app").contains(&"main-f-2".into()));
  assert!(ids(&repo, "app").contains(&"payments-f".into()));
  let without_rules = RetentionPolicy {
    rules: vec![],
    ..policy
  };
  repo.update_retention_policy(&without_rules).unwrap();
  assert_eq!(ids(&repo, "app").len(), 3);
}

#[test]
fn persisted_sqlite_policy_is_shared_and_invalid_updates_are_atomic() {
  let dir = tempfile::tempdir().unwrap();
  let path = dir.path().join("retention.sqlite");
  let repo =
    Repository::connect_sqlite(path.to_str().unwrap(), &RetentionPolicy::default()).unwrap();
  let policy = RetentionPolicy {
    passed_runs_per_app: 0,
    failed_runs_per_app: 1,
    rules: vec![],
  };
  repo.update_retention_policy(&policy).unwrap();
  let second = Repository::connect_sqlite(path.to_str().unwrap(), &equal_retention(99)).unwrap();
  assert_eq!(second.storage_stats().unwrap().retention, policy);
  for i in 0..3 {
    seed(&second, &format!("f-{i}"), "app", true, &[]);
  }
  assert_eq!(ids(&repo, "app"), ["f-2"]);
  let invalid = RetentionPolicy {
    rules: vec![RetentionRule {
      app_name: None,
      metadata: BTreeMap::from([("team".into(), vec![])]),
      passed_runs_per_app: None,
      failed_runs_per_app: Some(1),
    }],
    ..policy.clone()
  };
  assert!(repo.update_retention_policy(&invalid).is_err());
  assert_eq!(second.storage_stats().unwrap().retention, policy);
  assert_eq!(ids(&second, "app"), ["f-2"]);
  drop(repo);
  drop(second);
  let restarted = Repository::connect_sqlite(path.to_str().unwrap(), &equal_retention(99)).unwrap();
  assert_eq!(restarted.storage_stats().unwrap().retention, policy);
}

#[test]
fn configuration_file_environment_and_cli_set_persisted_policy() {
  use std::process::Command;
  let dir = tempfile::tempdir().unwrap();
  let config = dir.path().join("stove.toml");
  std::fs::write(
    &config,
    r#"
retention_passed_runs_per_app = 2
retention_failed_runs_per_app = 7
[[retention_rules]]
failed_runs_per_app = 12
[retention_rules.metadata]
team = ["checkout", "payments"]
"#,
  )
  .unwrap();
  for (case, expected) in [(0, (2, 7, 12)), (1, (4, 9, 15)), (2, (6, 11, 20))] {
    let db = dir.path().join(format!("{case}.sqlite"));
    let mut command = Command::new(env!("CARGO_BIN_EXE_stove"));
    for (name, _) in std::env::vars_os() {
      if name.to_string_lossy().starts_with("STOVE_") {
        command.env_remove(name);
      }
    }
    command
      .args(["--clear", "--no-skills-check", "--db"])
      .arg(&db)
      .arg("--config-file")
      .arg(&config);
    if case >= 1 {
      command
        .env("STOVE_RETENTION_PASSED_RUNS_PER_APP", "4")
        .env("STOVE_RETENTION_FAILED_RUNS_PER_APP", "9")
        .env(
          "STOVE_RETENTION_RULES",
          r#"[{"metadata":{"team":["checkout"]},"failed_runs_per_app":15}]"#,
        );
    }
    if case >= 2 {
      command.args([
        "--retention-passed-runs-per-app",
        "6",
        "--retention-failed-runs-per-app",
        "11",
        "--retention-rules",
        r#"[{"metadata":{"team":["checkout"]},"failed_runs_per_app":20}]"#,
      ]);
    }
    let output = command.output().unwrap();
    assert!(output.status.success(), "{output:?}");
    let policy = Repository::connect_sqlite(db.to_str().unwrap(), &equal_retention(99))
      .unwrap()
      .storage_stats()
      .unwrap()
      .retention;
    assert_eq!(
      (
        policy.passed_runs_per_app,
        policy.failed_runs_per_app,
        policy.rules[0].failed_runs_per_app.unwrap()
      ),
      expected
    );
  }
}

#[test]
fn sqlite_pruning_matches_shared_contract() {
  let repo = Repository::connect_sqlite(":memory:", &equal_retention(0)).unwrap();
  retention_contract::assert_pruning_contract(&repo);
}

#[test]
fn reducing_large_sqlite_history_respects_parameter_limits() {
  let repo = Repository::connect_sqlite(":memory:", &equal_retention(0)).unwrap();
  repo
    .execute_database_query(
      "WITH RECURSIVE history(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM history WHERE n < 33000)
     INSERT INTO runs (id, app_name, started_at, status, metadata)
     SELECT printf('run-%05d', n), 'app', '2024-01-01T00:00:00Z', 'PASSED', '{}' FROM history",
      1,
    )
    .unwrap();
  repo
    .update_retention_policy(&RetentionPolicy::default())
    .unwrap();
  assert_eq!(ids(&repo, "app"), ["run-32998", "run-32999", "run-33000"]);
}
