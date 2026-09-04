export function isFailed(result: string): boolean {
  const upper = result.toUpperCase();
  return upper === "FAILED" || upper === "ERROR";
}

export function isSuccessful(result: string): boolean {
  const upper = result.toUpperCase();
  return upper === "PASSED" || upper === "OK";
}

export function getResultTone(result: string): "failed" | "success" | "neutral" {
  if (isFailed(result)) return "failed";
  if (isSuccessful(result)) return "success";
  return "neutral";
}
