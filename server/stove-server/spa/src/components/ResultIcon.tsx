import { getResultTone } from "../utils/result";

export function ResultIcon({ result }: { result: string }) {
  switch (getResultTone(result)) {
    case "failed":
      return <span style={{ color: "var(--stove-red)" }}>✗</span>;
    case "success":
      return <span style={{ color: "var(--stove-green)" }}>✓</span>;
    case "neutral":
      return <span style={{ color: "var(--stove-text-secondary)" }}>•</span>;
  }
}
