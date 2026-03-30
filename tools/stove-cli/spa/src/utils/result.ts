export function isFailed(result: string): boolean {
  return result === "FAILED" || result === "ERROR";
}
