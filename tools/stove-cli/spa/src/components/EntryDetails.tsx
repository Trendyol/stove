import type { Entry } from "../api/types";
import { Detail } from "./Detail";

export function EntryDetails({ entry }: { entry: Entry }) {
  return (
    <>
      {entry.input && <Detail label="Input" value={entry.input} />}
      {entry.output && <Detail label="Output" value={entry.output} color="var(--stove-green)" />}
      {entry.expected && <Detail label="Expected" value={entry.expected} />}
      {entry.actual && <Detail label="Actual" value={entry.actual} />}
      {entry.error && <Detail label="Error" value={entry.error} color="var(--stove-red)" />}
      {entry.metadata && entry.metadata !== "{}" && (
        <Detail label="Metadata" value={entry.metadata} />
      )}
    </>
  );
}
