import { type RefObject, useLayoutEffect } from "react";
import { useEvidenceViewMemory } from "../components/evidence/EvidenceViewMemory";

export function useRememberedScroll(
  ref: RefObject<HTMLElement | null>,
  key: string,
  active = true,
) {
  const memory = useEvidenceViewMemory();
  useLayoutEffect(() => {
    const element = ref.current;
    if (!active || !element || !memory) return;
    const saved = memory.get(`scroll.${key}`) as number | undefined;
    if (saved !== undefined) element.scrollTop = saved;
    const save = () => memory.set(`scroll.${key}`, element.scrollTop);
    element.addEventListener("scroll", save);
    return () => {
      save();
      element.removeEventListener("scroll", save);
    };
  }, [key, memory, ref, active]);
}
