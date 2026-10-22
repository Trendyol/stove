/** Idle timeout is stored in seconds; zero disables automatic abandonment. */
export const DEFAULT_IDLE_TIMEOUT_SECONDS = 3600;
export const MAX_IDLE_TIMEOUT_SECONDS = 2_147_483_647;

export function isIdleTimeout(value: unknown): value is number {
  return (
    typeof value === "number" &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= MAX_IDLE_TIMEOUT_SECONDS
  );
}
