import { useState } from "react";
import type { Entry } from "../api/types";
import { tryFormatJson } from "../utils/json";

interface KafkaExplorerProps {
  entries: Entry[];
}

export function KafkaExplorer({ entries }: KafkaExplorerProps) {
  const kafkaEntries = entries.filter((e) => e.system === "Kafka");

  if (kafkaEntries.length === 0) {
    return <div className="text-[var(--stove-text-secondary)] text-sm p-4">No Kafka messages</div>;
  }

  const consumed = kafkaEntries.filter((e) => e.action.toLowerCase().includes("consum")).length;
  const published = kafkaEntries.filter((e) => e.action.toLowerCase().includes("publish")).length;
  const failed = kafkaEntries.filter((e) => e.result === "FAILED").length;

  return (
    <div className="p-3 space-y-2">
      <div className="flex gap-4 text-xs font-mono mb-3">
        <span className="text-[var(--stove-blue)]">{consumed} consumed</span>
        <span className="text-[var(--stove-green)]">{published} published</span>
        {failed > 0 && <span className="text-[var(--stove-red)]">{failed} failed</span>}
      </div>
      {kafkaEntries.map((entry) => (
        <KafkaMessageRow key={entry.id} entry={entry} />
      ))}
    </div>
  );
}

function KafkaMessageRow({ entry }: { entry: Entry }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <button
      type="button"
      className="bg-stove-card border border-stove-border rounded cursor-pointer hover:bg-[var(--stove-hover)] w-full text-left"
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 px-3 py-2 text-sm">
        <span
          className="text-xs px-1.5 py-0.5 rounded font-mono"
          style={{
            backgroundColor:
              entry.result === "FAILED" ? "var(--stove-red-bg)" : "var(--stove-amber-bg)",
            color: entry.result === "FAILED" ? "var(--stove-red)" : "var(--stove-amber)",
          }}
        >
          {entry.action}
        </span>
        {entry.trace_id && (
          <span className="text-[var(--stove-text-muted)] text-xs truncate">{entry.trace_id}</span>
        )}
      </div>

      {expanded && entry.output && (
        <div className="px-3 pb-3 border-t border-stove-border">
          <pre className="mt-2 p-2 bg-stove-base rounded text-xs text-[var(--stove-text)] whitespace-pre-wrap">
            {tryFormatJson(entry.output)}
          </pre>
        </div>
      )}
    </button>
  );
}
