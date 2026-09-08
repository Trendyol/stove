import { createContext, useContext, useEffect, useMemo, useRef } from "react";
import type { Tab } from "../layout/detail/TabBar";
import {
  appPath,
  type EvidenceFocus,
  type EvidenceKind,
  evidencePath,
  focusTab,
  navigateTo,
  useLocation,
} from "../utils/location";

interface EvidenceNavigation {
  runId: string;
  testId?: string;
  focus?: EvidenceFocus;
  pointer?: string;
  traceId?: string;
  openTrace: (traceId: string) => void;
  tab: Tab;
  full: boolean;
  context: number;
  moreContext: () => void;
  errorOpen: boolean;
  select: (kind: EvidenceKind, id: number, testId?: string | null) => void;
  clear: () => void;
  selectTab: (tab: Tab) => void;
  showFull: () => void;
  openError: () => void;
  href: string;
}
const Context = createContext<EvidenceNavigation | undefined>(undefined);
export const useEvidenceNavigation = () => useContext(Context);
export function useRequiredEvidenceNavigation() {
  const navigation = useEvidenceNavigation();
  if (!navigation) throw new Error("Evidence navigation provider is required");
  return navigation;
}

export function EvidenceNavigationProvider({
  runId,
  testId,
  children,
}: {
  runId: string;
  testId?: string;
  children: React.ReactNode;
}) {
  const location = useLocation();
  const view =
    location.kind === "evidence" &&
    location.value.runId === runId &&
    location.value.testId === testId
      ? location.value
      : undefined;
  const destinations = useRef(new Map<Tab, string>());
  useEffect(() => {
    destinations.current.clear();
  }, [runId, testId]);
  useEffect(() => {
    if (view)
      destinations.current.set(view.tab, `${evidencePath(runId, testId)}${window.location.search}`);
  }, [runId, testId, view]);
  const value = useMemo<EvidenceNavigation>(() => {
    const path = evidencePath(runId, testId);
    const update = (tab: Tab, target?: string, full = false) =>
      navigateTo(`${path}?tab=${tab}${target ? `&focus=${target}` : ""}${full ? "&full=1" : ""}`);
    return {
      runId,
      testId,
      context: view?.context ?? 10,
      moreContext: () => {
        const params = new URLSearchParams(window.location.search);
        params.set("context", String(Math.min(100, (view?.context ?? 10) + 10)));
        navigateTo(`${path}?${params}`, true);
      },
      focus: view?.focus,
      pointer: view?.pointer,
      traceId: view?.traceId,
      openTrace: (traceId) => navigateTo(`${path}?tab=trace&trace=${encodeURIComponent(traceId)}`),
      tab: view?.tab ?? "timeline",
      full: view?.full ?? false,
      errorOpen: view?.errorOpen ?? false,
      select: (kind, id, owner) =>
        navigateTo(
          `${evidencePath(runId, owner === null ? undefined : (owner ?? testId))}?tab=${focusTab(kind)}&focus=${kind}:${id}${view?.full ? "&full=1" : ""}`,
        ),
      clear: () => update(view?.tab ?? "timeline"),
      selectTab: (tab) => navigateTo(destinations.current.get(tab) ?? `${path}?tab=${tab}`),
      showFull: () => {
        if (!testId) {
          navigateTo(path);
          return;
        }
        const params = new URLSearchParams(window.location.search);
        params.set("full", "1");
        navigateTo(`${path}?${params}`);
      },
      openError: () => update(view?.tab ?? "timeline", "error"),
      href: `${window.location.origin}${window.location.pathname === appPath(path) ? window.location.pathname + window.location.search : appPath(path)}`,
    };
  }, [
    runId,
    testId,
    view?.tab,
    view?.focus?.kind,
    view?.focus?.id,
    view?.pointer,
    view?.traceId,
    view?.full,
    view?.errorOpen,
    view?.context,
  ]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
