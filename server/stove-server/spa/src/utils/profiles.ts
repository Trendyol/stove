import type { ProfileSeries } from "../api/types";

export type ProfileFrame = ProfileSeries["hotspots"][number]["frame"];
export const frameKey = (frame: ProfileFrame) =>
  JSON.stringify([frame.name, frame.file, frame.line]);

export function profileLabel(metric: string): string {
  const labels: Record<string, string> = {
    "process_cpu:cpu": "CPU time",
    "cpu:cpu": "CPU time",
    "wall:wall": "Wall time",
    "memory:alloc_in_new_tlab_bytes": "Allocated bytes · TLAB",
    "memory:alloc_in_new_tlab_objects": "Allocated objects · TLAB",
    "memory:alloc_outside_tlab_bytes": "Allocated bytes · outside TLAB",
    "memory:alloc_outside_tlab_objects": "Allocated objects · outside TLAB",
    "memory:alloc_sample_bytes": "Sampled allocation bytes",
    "memory:alloc_sample_objects": "Sampled allocation objects",
    "memory:live": "Surviving objects",
  };
  return labels[metric] ?? metric.replace(/_/g, " ").replace(":", " · ");
}

export function profileOrder(series: ProfileSeries): number {
  if (series.metric.endsWith(":cpu")) return 0;
  if (series.unit === "bytes") return 1;
  return 2;
}

export function formatProfileValue(value: number, unit: string): string {
  const number = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  if (unit === "nanoseconds") {
    for (const [scale, suffix] of [
      [1e9, "s"],
      [1e6, "ms"],
      [1e3, "µs"],
    ] as const) {
      if (Math.abs(value) >= scale) return `${number(value / scale)} ${suffix}`;
    }
    return `${number(value)} ns`;
  }
  if (unit === "bytes") {
    for (const [scale, suffix] of [
      [1024 ** 3, "GiB"],
      [1024 ** 2, "MiB"],
      [1024, "KiB"],
    ] as const) {
      if (Math.abs(value) >= scale) return `${number(value / scale)} ${suffix}`;
    }
    return `${number(value)} B`;
  }
  return unit === "count" ? number(value) : `${number(value)} ${unit}`;
}

export function shortMethod(name: string): string {
  const segments = name.split(/[/.]/);
  return segments.slice(-2).join(".");
}
