import type { DatabaseTable } from "../../api/types";

export const DEFAULT_MAX_ROWS = 100;

export type QueryTemplate = "select" | "insert" | "update" | "delete";

export function templateSql(kind: QueryTemplate, table: DatabaseTable): string {
  const tableName = quoteIdentifier(table.name);
  const primaryKey = table.columns.find((column) => column.primary_key) ?? table.columns[0];
  const writable = table.columns.find((column) => !column.primary_key) ?? table.columns[0];
  const primaryKeyName = quoteIdentifier(primaryKey?.name ?? "id");
  const writableName = quoteIdentifier(writable?.name ?? "column");

  switch (kind) {
    case "insert":
      return `INSERT INTO ${tableName} (${writableName})\nVALUES ('value');`;
    case "update":
      return `UPDATE ${tableName}\nSET ${writableName} = 'value'\nWHERE ${primaryKeyName} = 'value';`;
    case "delete":
      return `DELETE FROM ${tableName}\nWHERE ${primaryKeyName} = 'value';`;
    default:
      return selectSql(table);
  }
}

export function selectSql(table: DatabaseTable): string {
  return `SELECT * FROM ${quoteIdentifier(table.name)} LIMIT ${DEFAULT_MAX_ROWS};`;
}

function quoteIdentifier(identifier: string): string {
  return `"${identifier.replace(/"/g, '""')}"`;
}

export function isReadStatement(sql: string): boolean {
  const statement = sql.trimStart();
  return /^select\b/i.test(statement) && !/\binto\b/i.test(statement);
}

export function normalizeMaxRows(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_MAX_ROWS;
  return Math.min(500, Math.max(1, Math.trunc(value)));
}
