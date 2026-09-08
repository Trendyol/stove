import { useMemo, useSyncExternalStore } from "react";
import type { Tab } from "../layout/detail/TabBar";

export type EvidenceKind = "entry" | "span" | "snapshot" | "interaction" | "warning";
export interface EvidenceFocus {
  kind: EvidenceKind;
  id: number;
}
export interface EvidenceLocation {
  runId: string;
  testId?: string;
  tab: Tab;
  focus?: EvidenceFocus;
  pointer?: string;
  traceId?: string;
  errorOpen: boolean;
  full: boolean;
  context: number;
}
export type Location =
  | { kind: "home" }
  | { kind: "admin" }
  | { kind: "invalid" }
  | { kind: "evidence"; value: EvidenceLocation };
export const basePath = () =>
  typeof document === "undefined"
    ? ""
    : (document.querySelector('meta[name="stove-base"]')?.getAttribute("content") ?? "");
export const appPath = (path: string) => `${basePath()}${path}`;
export const encodeComponent = (value: string) =>
  encodeURIComponent(value).replace(
    /[!'()*]/g,
    (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`,
  );
export const evidencePath = (run: string, test?: string) =>
  `/runs/${encodeComponent(run)}${test === undefined ? "" : `/tests/${encodeComponent(test)}`}`;
export const focusTab = (kind: EvidenceKind): Tab =>
  (
    ({
      entry: "timeline",
      span: "trace",
      snapshot: "snapshots",
      interaction: "mocks",
      warning: "mocks",
    }) as const
  )[kind];

export function parseLocation(pathname: string, search: string): Location {
  const path = pathname.replace(/\/+$/, "") || "/";
  if (path === "/") return { kind: "home" };
  if (path === "/admin") return { kind: "admin" };
  const match = /^\/runs\/([^/]+)(?:\/tests\/([^/]+))?$/.exec(path);
  if (!match) return { kind: "invalid" };
  try {
    const runId = decodeURIComponent(match[1]);
    const testId = match[2] === undefined ? undefined : decodeURIComponent(match[2]);
    if (!runId || testId === "") return { kind: "invalid" };
    const params = new URLSearchParams(search);
    for (const key of ["tab", "focus", "pointer", "full", "context", "trace"])
      if (params.getAll(key).length > 1) return { kind: "invalid" };
    const tab = params.get("tab") ?? "timeline";
    if (!["timeline", "trace", "snapshots", "mocks", "flow"].includes(tab))
      return { kind: "invalid" };
    let focus: EvidenceFocus | undefined;
    const target = params.get("focus");
    if (target !== null && target !== "error") {
      const parts = /^(entry|span|snapshot|interaction|warning):([1-9]\d*)$/.exec(target);
      if (!parts || !Number.isSafeInteger(Number(parts[2]))) return { kind: "invalid" };
      focus = { kind: parts[1] as EvidenceKind, id: Number(parts[2]) };
      if (!testId && ["entry", "snapshot"].includes(focus.kind)) return { kind: "invalid" };
    }
    if (target === "error" && !testId) return { kind: "invalid" };
    const traceId = params.get("trace") ?? undefined;
    if (traceId !== undefined && (!traceId.trim() || tab !== "trace" || target !== null))
      return { kind: "invalid" };
    const pointer = params.get("pointer") ?? undefined;
    if (
      pointer !== undefined &&
      (focus?.kind !== "snapshot" ||
        (pointer !== "" && (!pointer.startsWith("/") || /~(?![01])/u.test(pointer))))
    )
      return { kind: "invalid" };
    if (params.has("full") && params.get("full") !== "1") return { kind: "invalid" };
    if (params.has("context") && !/^(0|[1-9]\d*)$/.test(params.get("context") ?? ""))
      return { kind: "invalid" };
    const context = params.has("context") ? Number(params.get("context")) : 10;
    if (!Number.isInteger(context) || context < 0 || context > 100) return { kind: "invalid" };
    return {
      kind: "evidence",
      value: {
        runId,
        testId,
        tab: focus ? focusTab(focus.kind) : (tab as Tab),
        focus,
        pointer,
        traceId,
        errorOpen: target === "error",
        context,
        full: params.get("full") === "1",
      },
    };
  } catch {
    return { kind: "invalid" };
  }
}

const subscribe = (listener: () => void) => {
  window.addEventListener("popstate", listener);
  return () => window.removeEventListener("popstate", listener);
};
const snapshot = () => window.location.pathname + window.location.search;
export function useLocation(): Location {
  const location = useSyncExternalStore(subscribe, snapshot);
  const prefix = basePath();
  return useMemo(() => {
    const index = location.indexOf("?");
    const pathname = index < 0 ? location : location.slice(0, index);
    const search = index < 0 ? "" : location.slice(index);
    const relative =
      prefix && pathname.startsWith(`${prefix}/`)
        ? pathname.slice(prefix.length)
        : pathname === prefix
          ? "/"
          : pathname;
    return parseLocation(relative, search);
  }, [location, prefix]);
}
export function navigateTo(path: string, replace = false) {
  const destination = appPath(path);
  if (destination === snapshot()) return;
  window.history[replace ? "replaceState" : "pushState"](null, "", destination);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
