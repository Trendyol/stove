import { useId, useLayoutEffect, useRef } from "react";
import type { Entry } from "../api/types";
import { useIsPhone, usePhoneNavigation } from "../hooks/usePhoneNavigation";
import { useRememberedScroll } from "../hooks/useRememberedScroll";
import { EvidenceCommandBar } from "./evidence/EvidenceCommandBar";
import { EvidenceInspector } from "./evidence/EvidenceInspector";
import { EvidenceLedger } from "./evidence/EvidenceLedger";
import { useEvidenceViewMemory } from "./evidence/EvidenceViewMemory";
import { isEntryIssue } from "./evidence/model";
import { useEvidenceWorkbench } from "./evidence/useEvidenceWorkbench";

interface EvidenceWorkbenchProps {
  entries: Entry[];
  onOpenTrace: (traceId: string) => void;
}

export function EvidenceWorkbench({ entries, onOpenTrace }: EvidenceWorkbenchProps) {
  const workbench = useEvidenceWorkbench(entries);
  const inspectorId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const memory = useEvidenceViewMemory();
  const splitRef = useRef<HTMLDivElement>(null);
  useRememberedScroll(splitRef, "timeline.split");
  const originRef = useRef<HTMLElement | null>(null);
  const isPhone = useIsPhone();
  const phone = usePhoneNavigation();
  const showInspector = !isPhone || !phone || phone.level === "event";
  const lastLevel = useRef(phone?.level);
  useLayoutEffect(() => {
    if (!isPhone || !phone) return;
    if (phone.level === "event" && phone.entryId !== undefined)
      workbench.selectEntry(phone.entryId);
    if (lastLevel.current === "event" && phone.level === "test") {
      const scroll = memory?.get("phone.returnScroll") as
        | { split: number; ledger: number; entryId: number }
        | undefined;
      if (scroll) {
        if (splitRef.current) splitRef.current.scrollTop = scroll.split;
        const ledger = rootRef.current?.querySelector<HTMLElement>(".evidence-ledger");
        if (ledger) ledger.scrollTop = scroll.ledger;
      }
      const origin = originRef.current?.isConnected
        ? originRef.current
        : rootRef.current?.querySelector<HTMLElement>(
            scroll
              ? `[data-entry-id="${scroll.entryId}"]`
              : '.evidence-ledger-row[aria-pressed="true"]',
          );
      origin?.focus({ preventScroll: true });
    }
    lastLevel.current = phone.level;
  }, [isPhone, phone?.level, phone?.entryId]);
  const selectRow = (id: number) => {
    originRef.current =
      rootRef.current?.querySelector<HTMLElement>(`[data-entry-id="${id}"]`) ?? null;
    if (isPhone)
      memory?.set("phone.returnScroll", {
        entryId: id,
        split: splitRef.current?.scrollTop ?? 0,
        ledger: rootRef.current?.querySelector<HTMLElement>(".evidence-ledger")?.scrollTop ?? 0,
      });
    workbench.selectEntry(id);
    if (isPhone) phone?.go({ level: "event", entryId: id, testId: phone.testId });
  };
  const closeInspector = () => {
    if (isPhone && phone) phone.back();
    else workbench.closeInspector();
  };
  return (
    <div ref={rootRef} className="evidence-workbench">
      <EvidenceCommandBar
        total={entries.length}
        issueCount={workbench.issueCount}
        filter={workbench.filter}
        search={workbench.search}
        onFilterChange={workbench.setFilter}
        onSearchChange={workbench.setSearch}
        onJumpToIssue={() => {
          workbench.jumpToFirstIssue();
          if (isPhone && phone) {
            const issue = entries.find(isEntryIssue);
            if (issue) phone.go({ level: "event", entryId: issue.id, testId: phone.testId });
          }
        }}
      />
      {workbench.newIssue && (
        <button
          type="button"
          className="new-evidence-failure"
          onClick={() => {
            workbench.setFilter("all");
            workbench.setSearch("");
            if (workbench.newIssue) selectRow(workbench.newIssue.id);
            workbench.acknowledgeIssues();
          }}
        >
          New failure · View event
        </button>
      )}
      {workbench.inspectorState.kind === "open" && (
        <button
          type="button"
          className="view-evidence-details"
          aria-controls={inspectorId}
          onClick={() => document.getElementById(`${inspectorId}-heading`)?.focus()}
        >
          View details
        </button>
      )}
      <div
        ref={splitRef}
        className={`evidence-split ${showInspector && workbench.inspectorState.kind === "open" ? "has-inspector" : ""}`}
      >
        <EvidenceLedger
          entries={workbench.visibleEntries}
          selectedId={workbench.selectedId}
          filtered={entries.length > 0}
          onSelect={selectRow}
          inspectorId={inspectorId}
        />
        <EvidenceInspector
          state={showInspector ? workbench.inspectorState : { kind: "closed" }}
          onSelect={(id) => {
            workbench.selectEntry(id);
            if (isPhone && phone)
              phone.go({ level: "event", entryId: id, testId: phone.testId }, true);
          }}
          inspectorId={inspectorId}
          onClose={closeInspector}
          onOpenTrace={onOpenTrace}
        />
      </div>
    </div>
  );
}
