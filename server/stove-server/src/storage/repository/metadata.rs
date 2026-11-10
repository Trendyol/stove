use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// OR within each key, AND across keys. Callers enforce their own selector validation.
pub(super) fn matches_metadata<'a>(
  selectors: impl IntoIterator<Item = (&'a String, &'a Vec<String>)>,
  metadata: &BTreeMap<String, String>,
) -> bool {
  selectors.into_iter().all(|(key, accepted)| {
    metadata
      .get(key)
      .is_some_and(|value| accepted.contains(value))
  })
}

/// Canonical OR-within-key, AND-across-keys selectors shared by REST and MCP.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct MetadataSelectors(BTreeMap<String, Vec<String>>);

impl MetadataSelectors {
  pub fn iter(&self) -> impl Iterator<Item = (&String, &Vec<String>)> {
    self.0.iter()
  }

  #[must_use]
  pub fn is_empty(&self) -> bool {
    self.0.is_empty()
  }
}

impl<'de> Deserialize<'de> for MetadataSelectors {
  fn deserialize<D: serde::Deserializer<'de>>(
    deserializer: D,
  ) -> std::result::Result<Self, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Values {
      One(String),
      Any(Vec<String>),
    }
    let raw = BTreeMap::<String, Values>::deserialize(deserializer)?;
    let mut selectors = BTreeMap::new();
    for (key, values) in raw {
      let mut values = match values {
        Values::One(value) => vec![value],
        Values::Any(values) => values,
      };
      if values.is_empty() {
        return Err(serde::de::Error::custom(
          "metadata values must not be empty",
        ));
      }
      values.sort();
      values.dedup();
      selectors.insert(key, values);
    }
    Ok(Self(selectors))
  }
}

impl From<BTreeMap<String, String>> for MetadataSelectors {
  fn from(values: BTreeMap<String, String>) -> Self {
    Self(
      values
        .into_iter()
        .map(|(key, value)| (key, vec![value]))
        .collect(),
    )
  }
}
