interface LedgerFilterButtonProps {
  active: boolean;
  count: number;
  label: string;
  onClick: () => void;
}

export function LedgerFilterButton({ active, count, label, onClick }: LedgerFilterButtonProps) {
  return (
    <button type="button" className={active ? "is-active" : ""} onClick={onClick}>
      {label}
      <span>{count}</span>
    </button>
  );
}
