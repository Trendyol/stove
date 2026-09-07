import { useRef } from "react";
import { useFocusShortcut } from "../hooks/useFocusShortcut";
import { Icon } from "./Icon";

interface LedgerSearchProps {
  label: string;
  placeholder?: string;
  value: string;
  onChange: (value: string) => void;
}

export function LedgerSearch({ label, placeholder = label, value, onChange }: LedgerSearchProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  useFocusShortcut(inputRef, "/");
  return (
    <label className="ledger-search">
      <Icon name="search" className="h-4 w-4" />
      <span className="sr-only">{label}</span>
      <input
        ref={inputRef}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
      />
      <kbd>/</kbd>
    </label>
  );
}
