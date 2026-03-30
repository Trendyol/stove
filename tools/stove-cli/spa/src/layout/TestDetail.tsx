import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import type { Test } from "../api/types";
import { EntryRow } from "../components/EntryRow";
import { FlowTab } from "../components/FlowTab";
import { KafkaExplorer } from "../components/KafkaExplorer";
import { SnapshotCards } from "../components/SnapshotCards";
import { SpanTree } from "../components/SpanTree";
import type { Tab } from "./detail/TabBar";
import { TabBar } from "./detail/TabBar";
import { TestHeader } from "./detail/TestHeader";

interface TestDetailProps {
  runId: string;
  test: Test;
}

export function TestDetail({ runId, test }: TestDetailProps) {
  const [tab, setTab] = useState<Tab>("timeline");
  const liveRefetchInterval = test.status === "RUNNING" ? 2000 : false;

  const { data: entries = [] } = useQuery({
    queryKey: ["entries", runId, test.id],
    queryFn: () => api.getEntries(runId, test.id),
    refetchInterval: liveRefetchInterval,
  });

  const { data: spans = [], isLoading: spansLoading } = useQuery({
    queryKey: ["spans", runId, test.id],
    queryFn: () => api.getSpans(runId, test.id),
    refetchInterval: liveRefetchInterval,
  });

  const { data: snapshots = [], isLoading: snapshotsLoading } = useQuery({
    queryKey: ["snapshots", runId, test.id],
    queryFn: () => api.getSnapshots(runId, test.id),
    refetchInterval: liveRefetchInterval,
  });

  const kafkaCount = entries.filter((e) => e.system === "Kafka").length;
  const tabs = [
    { id: "timeline" as Tab, label: `Timeline (${entries.length})`, icon: "\u{1f4cb}" },
    { id: "trace" as Tab, label: `Trace (${spans.length})`, icon: "\u{1f50d}" },
    { id: "snapshots" as Tab, label: `Snapshots (${snapshots.length})`, icon: "\u{1f4f8}" },
    {
      id: "kafka" as Tab,
      label: `Kafka${kafkaCount > 0 ? ` (${kafkaCount})` : ""}`,
      icon: "\u{1f4e8}",
    },
    { id: "flow" as Tab, label: "Flow", icon: "\u{1f310}" },
  ];

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="px-4 py-3 border-b border-stove-border bg-stove-surface sticky top-0 z-10">
        <TestHeader test={test} />
        {test.error && (
          <div className="mt-2 px-3 py-2 bg-red-900/20 border border-red-900/30 rounded text-xs text-red-400 font-mono">
            {test.error}
          </div>
        )}
        <TabBar tabs={tabs} active={tab} onSelect={setTab} />
      </div>

      <div className={`flex-1 ${tab === "flow" ? "overflow-hidden" : "overflow-y-auto"}`}>
        {tab === "timeline" && (
          <div className="p-3 space-y-0.5">
            {entries.map((entry) => (
              <EntryRow key={entry.id} entry={entry} />
            ))}
            {entries.length === 0 && (
              <div className="text-[var(--stove-text-secondary)] text-sm p-4">
                No entries recorded
              </div>
            )}
          </div>
        )}
        {tab === "trace" &&
          (spansLoading ? (
            <div className="text-[var(--stove-text-secondary)] text-sm p-4">Loading traces...</div>
          ) : (
            <SpanTree spans={spans} />
          ))}
        {tab === "snapshots" &&
          (snapshotsLoading ? (
            <div className="text-[var(--stove-text-secondary)] text-sm p-4">
              Loading snapshots...
            </div>
          ) : (
            <SnapshotCards snapshots={snapshots} />
          ))}
        {tab === "kafka" && <KafkaExplorer entries={entries} />}
        {tab === "flow" && <FlowTab entries={entries} spans={spans} />}
      </div>
    </div>
  );
}
