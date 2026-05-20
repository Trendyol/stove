import { getSystemInfo } from "../utils/systems";

interface SysBadgeProps {
  system: string;
}

export function SysBadge({ system }: SysBadgeProps) {
  const info = getSystemInfo(system);
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-mono text-[11px] font-medium"
      style={{
        color: info.color,
        backgroundColor: `${info.color}15`,
        borderColor: `${info.color}35`,
      }}
    >
      {info.icon} {system}
    </span>
  );
}
