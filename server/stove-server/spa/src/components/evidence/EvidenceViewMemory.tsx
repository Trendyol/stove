import { createContext, type ReactNode, useContext, useEffect, useRef, useState } from "react";

const EvidenceViewMemory = createContext<Map<string, unknown> | null>(null);

/** State belongs to this test and survives unmounting an investigation tab. */
export function EvidenceViewMemoryProvider({ children }: { children: ReactNode }) {
  const memory = useRef(new Map<string, unknown>());
  return (
    <EvidenceViewMemory.Provider value={memory.current}>{children}</EvidenceViewMemory.Provider>
  );
}

export function useEvidenceViewMemory() {
  return useContext(EvidenceViewMemory);
}

export function useRememberedState<T>(key: string, initial: T | (() => T)) {
  const memory = useEvidenceViewMemory();
  const state = useState<T>(() =>
    memory?.has(key)
      ? (memory.get(key) as T)
      : typeof initial === "function"
        ? (initial as () => T)()
        : initial,
  );
  const [value] = state;
  useEffect(() => {
    memory?.set(key, value);
  }, [memory, key, value]);
  return state;
}

/** Reveal a changed URL target; returning to an unchanged target retains its filters. */
export function useRevealTarget(key: string, target: string | undefined, reveal: () => void) {
  const memory = useEvidenceViewMemory();
  const previous = useRef(memory?.get(key));
  useEffect(() => {
    if (target && previous.current !== target) reveal();
    previous.current = target;
    memory?.set(key, target);
  }, [key, memory, target, reveal]);
}
