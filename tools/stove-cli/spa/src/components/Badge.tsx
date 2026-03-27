import { useTheme } from "../hooks/useTheme";

interface BadgeProps {
  status: string;
}

export function Badge({ status }: BadgeProps) {
  const { theme } = useTheme();
  const upper = status.toUpperCase();
  const configs = theme === "dark" ? DARK : LIGHT;
  const config = configs[upper] ?? configs.DEFAULT;

  return (
    <span
      role="status"
      aria-label={`Status: ${upper}`}
      className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-mono font-medium"
      style={{ backgroundColor: config.bg, color: config.text }}
    >
      {config.icon}
      {upper === "RUNNING" && (
        <span
          className="w-1.5 h-1.5 rounded-full animate-pulse-dot"
          style={{ backgroundColor: config.text }}
        />
      )}
      {upper !== "RUNNING" && upper}
    </span>
  );
}

interface BadgeStyle {
  bg: string;
  text: string;
  icon: string;
}

const DARK: Record<string, BadgeStyle> = {
  PASSED: { bg: "#06291e", text: "#34d399", icon: "\u2713 " },
  FAILED: { bg: "#2d0a0a", text: "#f87171", icon: "\u2717 " },
  ERROR: { bg: "#2d0a0a", text: "#f87171", icon: "\u2717 " },
  RUNNING: { bg: "#0f1d2e", text: "#60a5fa", icon: "" },
  DEFAULT: { bg: "#1e293b", text: "#94a3b8", icon: "" },
};

const LIGHT: Record<string, BadgeStyle> = {
  PASSED: { bg: "#d1fae5", text: "#065f46", icon: "\u2713 " },
  FAILED: { bg: "#fee2e2", text: "#991b1b", icon: "\u2717 " },
  ERROR: { bg: "#fee2e2", text: "#991b1b", icon: "\u2717 " },
  RUNNING: { bg: "#dbeafe", text: "#1e40af", icon: "" },
  DEFAULT: { bg: "#e5e7eb", text: "#4b5563", icon: "" },
};
