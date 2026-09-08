import { type RefObject, useEffect, useRef } from "react";

/** Owns focus, escape handling, and page scroll locking for a modal dialog. */
export function useModalDialog(
  open: boolean,
  onClose: () => void,
): RefObject<HTMLButtonElement | null> {
  const initialFocusRef = useRef<HTMLButtonElement>(null);

  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return;

    const previouslyFocused = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    initialFocusRef.current?.focus();

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeRef.current();
    };
    window.addEventListener("keydown", closeOnEscape);

    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", closeOnEscape);
      if (previouslyFocused instanceof HTMLElement) previouslyFocused.focus();
    };
  }, [open]);

  return initialFocusRef;
}
