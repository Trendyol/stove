/** Try to parse and pretty-print a JSON string. Returns the original string on failure. */
export function tryFormatJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
