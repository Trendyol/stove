use super::{TestSchema, connect_driver};
use crate::profiling::{ProfileQuery, StoredChunk};

#[test]
fn postgres_profile_stream_retention_filters_and_purge() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  repo
    .save_run_start("profiles", "app", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  for (service, offset) in [("a", 0), ("b", 300)] {
    for index in 0..300 {
      repo
        .save_profile(&StoredChunk {
          id: format!("{service}-{index:03}"),
          run_id: "profiles".into(),
          service: service.into(),
          instance: "one".into(),
          from_ms: index + offset,
          until_ms: index + offset + 1,
          payload: vec![1],
        })
        .unwrap();
    }
  }
  let chunks = repo.profile_chunks("profiles").unwrap();
  assert_eq!(chunks.len(), 512);
  assert_eq!(chunks[0].id, "a-299");
  assert_eq!(chunks[1].id, "b-299");
  let ids = repo
    .profile_chunk_ids(
      "profiles",
      &ProfileQuery {
        service: Some("b".into()),
        instance: Some("one".into()),
        from_ms: Some(599),
        until_ms: Some(600),
        ..Default::default()
      },
    )
    .unwrap();
  assert_eq!(ids, vec!["b-299"]);
  repo.purge_runs(&["profiles".into()], true).unwrap();
  assert!(repo.profile_chunks("profiles").unwrap().is_empty());
  assert!(repo.save_profile(&chunks[0]).is_err());
}

#[test]
fn postgres_profile_index_is_bounded_and_filters_precede_the_row_limit() {
  let Some(database) = TestSchema::from_env() else {
    return;
  };
  let repo = database.repository();
  repo
    .save_run_start("many", "app", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  let mut client = connect_driver(&database.url).unwrap();
  client.execute("INSERT INTO profiles (id,run_id,service,instance,from_ms,until_ms,payload,byte_size)
    SELECT 'chunk-' || i, 'many', 'service-' || i, 'one', 0, 1, $1, 1 FROM generate_series(1,10000) i", &[&vec![1_u8]]).unwrap();
  assert_eq!(
    repo
      .profile_chunk_ids("many", &ProfileQuery::default())
      .unwrap()
      .len(),
    4097
  );
  let ids = repo
    .profile_chunk_ids(
      "many",
      &ProfileQuery {
        service: Some("service-9999".into()),
        ..Default::default()
      },
    )
    .unwrap();
  assert_eq!(ids, vec!["chunk-9999"]);
}
