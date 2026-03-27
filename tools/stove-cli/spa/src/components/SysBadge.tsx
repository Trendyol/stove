import { getSystemInfo } from "../utils/systems";

interface SysBadgeProps {
  system: string;
}

export function SysBadge({ system }: SysBadgeProps) {
  const info = getSystemInfo(system);
  return (
    <span
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-mono"
      style={{ color: info.color, backgroundColor: `${info.color}15` }}
    >
      {info.icon} {system}
    </span>
  );
}
