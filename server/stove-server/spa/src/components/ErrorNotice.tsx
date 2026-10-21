import type { ReactNode } from "react";

interface ErrorNoticeProps {
  children: ReactNode;
  onRetry?: () => void;
  className?: string;
}

export function ErrorNotice({
  children,
  onRetry,
  className = "stove-admin-error flex items-center justify-between gap-3",
}: ErrorNoticeProps) {
  return (
    <div role="alert" className={className}>
      <span>{children}</span>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
