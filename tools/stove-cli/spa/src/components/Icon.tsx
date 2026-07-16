export type IconName =
  | "activity"
  | "chevron"
  | "flow"
  | "mock"
  | "search"
  | "snapshot"
  | "trace"
  | "warning";

interface IconProps {
  name: IconName;
  className?: string;
}

export function Icon({ name, className = "h-4 w-4" }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {name === "activity" && (
        <>
          <path d="M3 12h4l2.2-6 4.1 12 2.2-6H21" />
          <path d="M4 4v16M20 4v16" opacity=".35" />
        </>
      )}
      {name === "chevron" && <path d="m9 6 6 6-6 6" />}
      {name === "flow" && (
        <>
          <circle cx="5" cy="6" r="2" />
          <circle cx="19" cy="6" r="2" />
          <circle cx="12" cy="18" r="2" />
          <path d="M7 6h10M6.5 8l4.4 8M17.5 8l-4.4 8" />
        </>
      )}
      {name === "mock" && (
        <>
          <path d="M4 7h7M13 7h7M4 17h7M13 17h7" />
          <path d="m8 4 3 3-3 3M16 14l-3 3 3 3" />
        </>
      )}
      {name === "search" && (
        <>
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 4 4" />
        </>
      )}
      {name === "snapshot" && (
        <>
          <rect x="3" y="6" width="18" height="14" rx="3" />
          <path d="m8 6 1.4-2h5.2L16 6" />
          <circle cx="12" cy="13" r="3.5" />
        </>
      )}
      {name === "trace" && (
        <>
          <circle cx="5" cy="5" r="2" />
          <circle cx="19" cy="12" r="2" />
          <circle cx="7" cy="19" r="2" />
          <path d="M7 5h4a3 3 0 0 1 3 3v1a3 3 0 0 0 3 3M17 13h-4a3 3 0 0 0-3 3v0a3 3 0 0 1-3 3" />
        </>
      )}
      {name === "warning" && (
        <>
          <path d="M10.3 4.1 2.7 18a2 2 0 0 0 1.8 3h15a2 2 0 0 0 1.8-3L13.7 4.1a2 2 0 0 0-3.4 0Z" />
          <path d="M12 9v4M12 17h.01" />
        </>
      )}
    </svg>
  );
}
