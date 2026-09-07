export function InspectorDatum({
  label,
  value,
  tone = "neutral",
  mono = false,
}: {
  label: string;
  value: string;
  tone?: "neutral" | "warn" | "bad";
  mono?: boolean;
}) {
  return (
    <div className={`inspector-datum is-${tone}`}>
      <span>{label}</span>
      <strong className={mono ? "is-mono" : ""}>{value}</strong>
    </div>
  );
}
