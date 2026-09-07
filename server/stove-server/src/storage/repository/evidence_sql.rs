//! Bounded, portable traversal used by both storage engines.
pub(super) const SPAN_ANCESTORS: &str = "WITH RECURSIVE ancestry AS (
  SELECT id, parent_span_id, 0 AS depth FROM spans
    WHERE run_id = $1 AND trace_id = $2 AND id = $3
  UNION ALL
  SELECT parent.id, parent.parent_span_id, ancestry.depth + 1
    FROM ancestry JOIN spans parent ON parent.id = (
      SELECT candidate.id FROM spans candidate
        WHERE candidate.run_id = $1 AND candidate.trace_id = $2
          AND candidate.span_id = ancestry.parent_span_id
        ORDER BY candidate.id LIMIT 1
    ) WHERE ancestry.depth < $4
)
SELECT spans.* FROM ancestry JOIN spans ON spans.id = ancestry.id ORDER BY ancestry.depth";
