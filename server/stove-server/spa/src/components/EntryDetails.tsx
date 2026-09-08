import type { Entry } from "../api/types";
import { Detail } from "./Detail";

export function EntryDetails({ entry }: { entry: Entry }) {
  return (
    <>
      {(entry.expected || entry.actual) && (
        <section className="evidence-comparison" aria-label="Expected and actual values">
          {entry.expected && <Detail label="Expected" value={entry.expected} />}
          {entry.actual && <Detail label="Actual" value={entry.actual} />}
        </section>
      )}
      {entry.error && <Detail label="Error" value={entry.error} color="var(--stove-red)" />}
      {entry.attempt_count > 1 && (
        <Detail
          label="Retry history"
          value={`${entry.attempt_count} attempts, ${entry.failure_count} failed`}
        />
      )}
      {entry.input && <Detail label="Input" value={entry.input} />}
      {entry.output && <Detail label="Output" value={entry.output} />}
      {entry.metadata && entry.metadata !== "{}" && (
        <Detail label="Metadata" value={entry.metadata} />
      )}
    </>
  );
}
