interface SystemInfo {
  color: string;
  icon: string;
}

const SYSTEM_MAP: Record<string, SystemInfo> = {
  HTTP: { color: "#60a5fa", icon: "\u21c4" },
  Kafka: { color: "#f59e0b", icon: "\u26a1" },
  PostgreSQL: { color: "#34d399", icon: "\u229e" },
  Postgres: { color: "#34d399", icon: "\u229e" },
  WireMock: { color: "#a78bfa", icon: "\u25ce" },
  gRPC: { color: "#fb923c", icon: "\u25c8" },
  "gRPC Mock": { color: "#fb923c", icon: "\u25c8" },
  Redis: { color: "#f87171", icon: "\u25c6" },
  MongoDB: { color: "#4ade80", icon: "\u2291" },
  Mongo: { color: "#4ade80", icon: "\u2291" },
  Couchbase: { color: "#06b6d4", icon: "\u2261" },
  Elasticsearch: { color: "#fbbf24", icon: "\u2315" },
  MySQL: { color: "#0ea5e9", icon: "\u229e" },
  MSSQL: { color: "#8b5cf6", icon: "\u229e" },
  Cassandra: { color: "#d946ef", icon: "\u2609" },
};

const DEFAULT_SYSTEM: SystemInfo = { color: "#94a3b8", icon: "\u2022" };

export function getSystemInfo(name: string): SystemInfo {
  return SYSTEM_MAP[name] ?? DEFAULT_SYSTEM;
}
