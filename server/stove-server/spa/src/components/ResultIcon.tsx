import { getResultTone } from "../utils/result";

export function ResultIcon({ result }: { result: string }) {
  const tone = getResultTone(result);
  const color =
    tone === "failed"
      ? "var(--stove-red)"
      : tone === "success"
        ? "var(--stove-green)"
        : "var(--stove-text-secondary)";
  const icon = tone === "failed" ? "\u2717" : tone === "success" ? "\u2713" : "\u2022";

  return <span style={{ color }}>{icon}</span>;
}
