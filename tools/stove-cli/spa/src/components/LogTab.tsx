import { useMemo, useState } from "react";
import type { LogRecord } from "../api/types";

type GroupMode = "none" | "level" | "logger" | "thread";

interface LogTabProps {
  logs: LogRecord[];
  runLevelLogs: LogRecord[];
  onOpenTraceTab: () => void;
}

const BASE_LEVELS = ["ERROR", "WARN", "INFO"] as const;
const OPTIONAL_LEVELS = ["DEBUG", "TRACE"] as const;

export function LogTab({ logs, runLevelLogs, onOpenTraceTab }: LogTabProps) {
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("ALL");
  const [groupMode, setGroupMode] = useState<GroupMode>("none");

  const levels = useMemo(() => {
    const captured = new Set([...logs, ...runLevelLogs].map((log) => log.severity_text));
    return [
      "ALL",
      ...BASE_LEVELS,
      ...OPTIONAL_LEVELS.filter((candidate) => captured.has(candidate)),
    ];
  }, [logs, runLevelLogs]);

  const filteredLogs = useMemo(() => filterLogs(logs, level, query), [logs, level, query]);
  const filteredRunLevelLogs = useMemo(
    () => filterLogs(runLevelLogs, level, query).filter((log) => !log.test_id),
    [runLevelLogs, level, query],
  );
  const groupedLogs = useMemo(() => groupLogs(filteredLogs, groupMode), [filteredLogs, groupMode]);

  return (
    <div className="p-3 space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search message, logger, thread, exception, MDC"
          className="min-w-64 flex-1 bg-stove-card border border-stove-border rounded px-3 py-2 text-sm text-[var(--stove-text)] outline-none focus:border-amber-500"
        />
        <select
          value={groupMode}
          onChange={(event) => setGroupMode(event.target.value as GroupMode)}
          className="bg-stove-card border border-stove-border rounded px-2 py-2 text-sm text-[var(--stove-text)] outline-none"
        >
          <option value="none">No grouping</option>
          <option value="level">Group by level</option>
          <option value="logger">Group by logger</option>
          <option value="thread">Group by thread</option>
        </select>
      </div>

      <div className="flex flex-wrap gap-1">
        {levels.map((candidate) => (
          <button
            type="button"
            key={candidate}
            onClick={() => setLevel(candidate)}
            className={`px-2.5 py-1 text-xs rounded border ${
              level === candidate
                ? "border-amber-500 bg-amber-500/15 text-amber-300"
                : "border-stove-border text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
            }`}
          >
            {candidate}
          </button>
        ))}
      </div>

      {filteredRunLevelLogs.length > 0 && (
        <section className="border border-stove-border bg-stove-surface/60 rounded">
          <div className="px-3 py-2 text-xs uppercase tracking-wide text-[var(--stove-text-secondary)] border-b border-stove-border">
            Run-level unassigned logs ({filteredRunLevelLogs.length})
          </div>
          <div className="divide-y divide-stove-border/60">
            {filteredRunLevelLogs.map((log) => (
              <LogRow key={log.id} log={log} onOpenTraceTab={onOpenTraceTab} />
            ))}
          </div>
        </section>
      )}

      {groupedLogs.map((group) => (
        <section
          key={group.name}
          className="border border-stove-border bg-stove-surface/60 rounded"
        >
          {groupMode !== "none" && (
            <div className="px-3 py-2 text-xs uppercase tracking-wide text-[var(--stove-text-secondary)] border-b border-stove-border">
              {group.name} ({group.logs.length})
            </div>
          )}
          <div className="divide-y divide-stove-border/60">
            {group.logs.map((log) => (
              <LogRow key={log.id} log={log} onOpenTraceTab={onOpenTraceTab} />
            ))}
          </div>
        </section>
      ))}

      {filteredLogs.length === 0 && filteredRunLevelLogs.length === 0 && (
        <div className="text-[var(--stove-text-secondary)] text-sm p-4">No logs recorded</div>
      )}
    </div>
  );
}

function LogRow({ log, onOpenTraceTab }: { log: LogRecord; onOpenTraceTab: () => void }) {
  const dropped = log.correlation_source === "DROPPED_MARKER";
  return (
    <article className={`px-3 py-2 ${severityTextClass(log.severity_text)}`}>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
        <span className={`font-semibold ${severityBadgeClass(log.severity_text)}`}>
          {log.severity_text}
        </span>
        <span className="font-mono text-[var(--stove-text-secondary)]">
          {formatTime(log.timestamp)}
        </span>
        <span className="font-mono text-[var(--stove-text-secondary)]">{log.thread}</span>
        <span className="font-mono text-[var(--stove-text)]">{log.logger}</span>
        {log.late && <span className="text-amber-300">late</span>}
        {log.truncated && <span className="text-amber-300">truncated</span>}
        {dropped && <span className="text-amber-300">dropped marker</span>}
        {log.trace_id && (
          <button
            type="button"
            onClick={onOpenTraceTab}
            className="text-sky-300 hover:text-sky-200 font-mono"
            title={log.span_id ? `span ${log.span_id}` : "Open trace tab"}
          >
            trace {shortId(log.trace_id)}
          </button>
        )}
      </div>
      <pre className="mt-1 whitespace-pre-wrap break-words font-mono text-xs leading-relaxed">
        {log.body}
      </pre>
      {(log.exception_message || log.exception_stack_trace) && (
        <pre className="mt-2 whitespace-pre-wrap break-words font-mono text-xs text-red-300/90">
          {[log.exception_message, log.exception_stack_trace].filter(Boolean).join("\n")}
        </pre>
      )}
    </article>
  );
}

function filterLogs(logs: LogRecord[], level: string, query: string): LogRecord[] {
  const needle = query.trim().toLowerCase();
  return logs.filter((log) => {
    if (level !== "ALL" && log.severity_text !== level) return false;
    if (!needle) return true;
    return [
      log.body,
      log.logger,
      log.thread,
      log.exception_type,
      log.exception_message,
      log.exception_stack_trace,
      log.attributes,
    ]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
}

function groupLogs(
  logs: LogRecord[],
  groupMode: GroupMode,
): Array<{ name: string; logs: LogRecord[] }> {
  if (groupMode === "none") {
    return logs.length > 0 ? [{ name: "All logs", logs }] : [];
  }
  const grouped = new Map<string, LogRecord[]>();
  for (const log of logs) {
    const key =
      groupMode === "level" ? log.severity_text : groupMode === "logger" ? log.logger : log.thread;
    grouped.set(key, [...(grouped.get(key) ?? []), log]);
  }
  return [...grouped.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, groupLogs]) => ({ name, logs: groupLogs }));
}

function severityBadgeClass(level: string): string {
  if (level === "ERROR") return "text-red-300";
  if (level === "WARN") return "text-amber-300";
  if (level === "DEBUG" || level === "TRACE") return "text-[var(--stove-text-secondary)]";
  return "text-[var(--stove-text)]";
}

function severityTextClass(level: string): string {
  if (level === "ERROR") return "bg-red-950/20";
  if (level === "WARN") return "bg-amber-950/20";
  if (level === "DEBUG" || level === "TRACE") return "opacity-75";
  return "";
}

function formatTime(timestamp: string): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return timestamp;
  return date.toLocaleTimeString();
}

function shortId(value: string): string {
  return value.length > 10 ? value.slice(0, 10) : value;
}
