import { type RefObject, useEffect } from "react";

/** Focuses a control from a global key without stealing keystrokes from editable elements. */
export function useFocusShortcut(target: RefObject<HTMLElement | null>, shortcut: string): void {
  useEffect(() => {
    const focusTarget = (event: KeyboardEvent) => {
      if (event.key !== shortcut || isEditableTarget(event.target)) return;
      event.preventDefault();
      target.current?.focus();
    };

    window.addEventListener("keydown", focusTarget);
    return () => window.removeEventListener("keydown", focusTarget);
  }, [shortcut, target]);
}

function isEditableTarget(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement ||
    (target instanceof HTMLElement && target.isContentEditable)
  );
}
