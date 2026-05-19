import { useMemo, useState } from "react";
import type { LogRecord, LogScope, Test } from "../api/types";

type GroupMode = "none" | "level" | "logger" | "thread" | "scope" | "test";
type ScopeFilter = "ALL" | LogScope;

interface RunLogsViewProps {
  logs: LogRecord[];
  tests: Test[];
}

const BASE_LEVELS = ["ERROR", "WARN", "INFO"] as const;
const OPTIONAL_LEVELS = ["DEBUG", "TRACE"] as const;
const UNATTRIBUTED_KEY = "__unattributed__";

export function RunLogsView({ logs, tests }: RunLogsViewProps) {
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("ALL");
  const [scopeFilter, setScopeFilter] = useState<ScopeFilter>("ALL");
  const [groupMode, setGroupMode] = useState<GroupMode>("none");
  const [collapseDuplicates, setCollapseDuplicates] = useState(true);

  const testNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const test of tests) {
      map.set(test.id, test.test_name);
    }
    return map;
  }, [tests]);

  const levels = useMemo(() => {
    const captured = new Set(logs.map((log) => log.severity_text));
    return [
      "ALL",
      ...BASE_LEVELS,
      ...OPTIONAL_LEVELS.filter((candidate) => captured.has(candidate)),
    ];
  }, [logs]);

  const filtered = useMemo(
    () => filterLogs(logs, level, scopeFilter, query),
    [logs, level, scopeFilter, query],
  );

  const { displayed, duplicateGroups } = useMemo(() => {
    if (!collapseDuplicates) {
      return { displayed: filtered, duplicateGroups: new Map<string, number>() };
    }
    return collapseDuplicateLogs(filtered);
  }, [filtered, collapseDuplicates]);

  const grouped = useMemo(
    () => groupLogs(displayed, groupMode, testNameById),
    [displayed, groupMode, testNameById],
  );

  const counts = useMemo(() => summarizeLogs(filtered), [filtered]);

  return (
    <div className="p-3 space-y-3">
      <header className="flex flex-wrap items-baseline gap-3 px-1">
        <h2 className="text-base font-semibold text-[var(--stove-text-heading)]">Run logs</h2>
        <span className="text-xs text-[var(--stove-text-secondary)]">
          {counts.total} entries · {counts.errors} errors · {counts.warns} warnings ·{" "}
          {counts.testAttributed} test-scoped · {counts.runScoped} run-scoped
        </span>
      </header>

      <div className="flex flex-wrap items-center gap-2">
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search message, logger, thread, exception, MDC"
          className="min-w-64 flex-1 bg-stove-card border border-stove-border rounded px-3 py-2 text-sm text-[var(--stove-text)] outline-none focus:border-amber-500"
        />
        <select
          value={scopeFilter}
          onChange={(event) => setScopeFilter(event.target.value as ScopeFilter)}
          className="bg-stove-card border border-stove-border rounded px-2 py-2 text-sm text-[var(--stove-text)] outline-none"
        >
          <option value="ALL">All scopes</option>
          <option value="RUN">Run scope only</option>
          <option value="TEST">Test scope only</option>
        </select>
        <select
          value={groupMode}
          onChange={(event) => setGroupMode(event.target.value as GroupMode)}
          className="bg-stove-card border border-stove-border rounded px-2 py-2 text-sm text-[var(--stove-text)] outline-none"
        >
          <option value="none">No grouping</option>
          <option value="level">Group by level</option>
          <option value="logger">Group by logger</option>
          <option value="thread">Group by thread</option>
          <option value="scope">Group by scope</option>
          <option value="test">Group by test</option>
        </select>
        <label className="flex items-center gap-1.5 text-xs text-[var(--stove-text-secondary)] cursor-pointer">
          <input
            type="checkbox"
            checked={collapseDuplicates}
            onChange={(event) => setCollapseDuplicates(event.target.checked)}
          />
          Collapse duplicates
        </label>
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

      {grouped.map((group) => (
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
              <LogRow
                key={String(log.id)}
                log={log}
                duplicateCount={duplicateGroups.get(duplicateKey(log)) ?? 1}
                testName={log.test_id ? (testNameById.get(log.test_id) ?? log.test_id) : null}
              />
            ))}
          </div>
        </section>
      ))}

      {displayed.length === 0 && (
        <div className="text-[var(--stove-text-secondary)] text-sm p-4">
          No logs match the current filters
        </div>
      )}
    </div>
  );
}

function LogRow({
  log,
  duplicateCount,
  testName,
}: {
  log: LogRecord;
  duplicateCount: number;
  testName: string | null;
}) {
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
        <ScopeBadge scope={log.scope} />
        {testName && (
          <span
            className="font-mono text-sky-300/90"
            title={log.test_id ? `test: ${log.test_id}` : undefined}
          >
            {testName}
          </span>
        )}
        {log.late && <span className="text-amber-300">late</span>}
        {log.truncated && <span className="text-amber-300">truncated</span>}
        {dropped && <span className="text-amber-300">dropped marker</span>}
        {duplicateCount > 1 && (
          <span className="text-[var(--stove-text-secondary)]" title="Identical messages collapsed">
            ×{duplicateCount}
          </span>
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

function ScopeBadge({ scope }: { scope: LogScope }) {
  const className =
    scope === "TEST"
      ? "border-sky-500/60 text-sky-300 bg-sky-500/10"
      : "border-stove-border text-[var(--stove-text-secondary)] bg-stove-card/60";
  return (
    <span
      className={`px-1.5 py-0.5 rounded border text-[10px] uppercase tracking-wide ${className}`}
      title={
        scope === "TEST"
          ? "Captured under an explicit Stove test context"
          : "Ambient run-level log (framework, background thread, etc.)"
      }
    >
      {scope}
    </span>
  );
}

function filterLogs(
  logs: LogRecord[],
  level: string,
  scope: ScopeFilter,
  query: string,
): LogRecord[] {
  const needle = query.trim().toLowerCase();
  return logs.filter((log) => {
    if (level !== "ALL" && log.severity_text !== level) return false;
    if (scope !== "ALL" && log.scope !== scope) return false;
    if (!needle) return true;
    return [
      log.body,
      log.logger,
      log.thread,
      log.exception_type,
      log.exception_message,
      log.exception_stack_trace,
      log.attributes,
      log.test_id,
    ]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(needle));
  });
}

function duplicateKey(log: LogRecord): string {
  return `${log.severity_text} ${log.logger} ${log.body}`;
}

function collapseDuplicateLogs(logs: LogRecord[]): {
  displayed: LogRecord[];
  duplicateGroups: Map<string, number>;
} {
  const counts = new Map<string, number>();
  for (const log of logs) {
    const key = duplicateKey(log);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const seen = new Set<string>();
  const displayed: LogRecord[] = [];
  for (const log of logs) {
    const key = duplicateKey(log);
    if (seen.has(key)) continue;
    seen.add(key);
    displayed.push(log);
  }
  return { displayed, duplicateGroups: counts };
}

function groupLogs(
  logs: LogRecord[],
  groupMode: GroupMode,
  testNameById: Map<string, string>,
): Array<{ name: string; logs: LogRecord[] }> {
  if (groupMode === "none") {
    return logs.length > 0 ? [{ name: "All logs", logs }] : [];
  }
  const grouped = new Map<string, LogRecord[]>();
  for (const log of logs) {
    const key = groupKey(log, groupMode, testNameById);
    grouped.set(key, [...(grouped.get(key) ?? []), log]);
  }
  return [...grouped.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, groupLogs]) => ({ name, logs: groupLogs }));
}

function groupKey(log: LogRecord, groupMode: GroupMode, testNameById: Map<string, string>): string {
  switch (groupMode) {
    case "level":
      return log.severity_text;
    case "logger":
      return log.logger;
    case "thread":
      return log.thread;
    case "scope":
      return log.scope;
    case "test":
      if (!log.test_id) return UNATTRIBUTED_KEY;
      return testNameById.get(log.test_id) ?? log.test_id;
    default:
      return "All logs";
  }
}

function summarizeLogs(logs: LogRecord[]) {
  let errors = 0;
  let warns = 0;
  let runScoped = 0;
  let testAttributed = 0;
  for (const log of logs) {
    if (log.severity_text === "ERROR") errors += 1;
    if (log.severity_text === "WARN") warns += 1;
    if (log.scope === "RUN") runScoped += 1;
    else testAttributed += 1;
  }
  return { total: logs.length, errors, warns, runScoped, testAttributed };
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
