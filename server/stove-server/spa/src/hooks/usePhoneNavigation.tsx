import { createContext, type ReactNode, useContext, useMemo, useSyncExternalStore } from "react";

import type { Tab } from "../layout/detail/TabBar";

type PhoneScreen = {
  level: "tests" | "test" | "event";
  testId?: string;
  entryId?: number;
  tab?: Tab;
  traceId?: string;
  backLabel?: string;
};
const subscribe = (listener: () => void) => {
  window.addEventListener("popstate", listener);
  return () => window.removeEventListener("popstate", listener);
};
const snapshot = () => JSON.stringify(window.history.state?.stovePhone ?? null);
const initial: PhoneScreen = { level: "tests" };
function usePhoneHistory(scope: string) {
  const value = useSyncExternalStore(subscribe, snapshot);
  return useMemo(() => {
    const stored = JSON.parse(value);
    const screen: PhoneScreen = stored?.scope === scope ? stored.screen : initial;
    return {
      ...screen,
      go: (next: PhoneScreen, replace = false) => {
        window.history[replace ? "replaceState" : "pushState"](
          { ...window.history.state, stovePhone: { scope, screen: next } },
          "",
        );
        window.dispatchEvent(new PopStateEvent("popstate"));
      },
      back: () => window.history.back(),
    };
  }, [scope, value]);
}
const Context = createContext<ReturnType<typeof usePhoneHistory> | null>(null);
export const usePhoneNavigation = () => useContext(Context);
export function PhoneNavigationProvider({
  scope,
  children,
}: {
  scope: string;
  children: ReactNode;
}) {
  return <Context.Provider value={usePhoneHistory(scope)}>{children}</Context.Provider>;
}
const subscribePhone = (listener: () => void) => {
  const media = window.matchMedia?.("(max-width: 720px)");
  media?.addEventListener("change", listener);
  return () => media?.removeEventListener("change", listener);
};
export const useIsPhone = () =>
  useSyncExternalStore(
    subscribePhone,
    () => window.matchMedia?.("(max-width: 720px)").matches ?? false,
  );
