import { isFailed } from "../utils/result";

export function ResultIcon({ result }: { result: string }) {
  const failed = isFailed(result);
  return (
    <span style={{ color: failed ? "var(--stove-red)" : "var(--stove-green)" }}>
      {failed ? "\u2717" : "\u2713"}
    </span>
  );
}
