/** Use the final metadata segment as the user-facing label; keep the full key in its tooltip. */
export function metadataLabel(key: string): string {
  const name = (key.split(".").filter(Boolean).pop() ?? key)
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim();
  return name ? name[0].toUpperCase() + name.slice(1) : key;
}
