import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import type { DatabaseQueryResult, DatabaseSchema, DatabaseTable } from "../../api/types";

const DEFAULT_MAX_ROWS = 100;

export function useDatabaseExplorer(onDatabaseChange: () => Promise<void>) {
  const queryClient = useQueryClient();
  const [schema, setSchema] = useState<DatabaseSchema | null>(null);
  const [selectedTable, setSelectedTable] = useState<DatabaseTable | null>(null);
  const [sql, setSql] = useState("");
  const [maxRows, setMaxRows] = useState(DEFAULT_MAX_ROWS);
  const [result, setResult] = useState<DatabaseQueryResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void api
      .getDatabaseSchema(controller.signal)
      .then((next) => {
        setSchema(next);
        const initial = next.tables.find((table) => table.name === "runs") ?? next.tables[0];
        if (initial) {
          setSelectedTable(initial);
          setSql(selectSql(initial));
        }
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(reason));
      });
    return () => controller.abort();
  }, []);

  const execute = async (statement = sql) => {
    if (!statement.trim()) return;
    const mutating = !isReadStatement(statement);
    if (
      mutating &&
      !confirm("Run this database-changing statement? Direct changes cannot be undone by Stove.")
    ) {
      return;
    }

    setBusy(true);
    setError(null);
    try {
      setSql(statement);
      setResult(await api.executeDatabaseQuery(statement, normalizeMaxRows(maxRows)));
      if (mutating) {
        await queryClient.resetQueries();
        await onDatabaseChange();
      }
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  };

  const selectTable = (table: DatabaseTable) => {
    setSelectedTable(table);
    setSql(selectSql(table));
    setResult(null);
  };

  return {
    schema,
    selectedTable,
    selectTable,
    sql,
    setSql,
    maxRows,
    setMaxRows,
    result,
    busy,
    error,
    execute,
    applyTemplate: (kind: QueryTemplate) => {
      if (selectedTable) setSql(templateSql(kind, selectedTable));
    },
  };
}

export type QueryTemplate = "select" | "insert" | "update" | "delete";

function templateSql(kind: QueryTemplate, table: DatabaseTable): string {
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

function selectSql(table: DatabaseTable): string {
  return `SELECT * FROM ${quoteIdentifier(table.name)} LIMIT ${DEFAULT_MAX_ROWS};`;
}

function quoteIdentifier(identifier: string): string {
  return `"${identifier.replace(/"/g, '""')}"`;
}

function isReadStatement(sql: string): boolean {
  const statement = sql.trimStart();
  return /^select\b/i.test(statement) && !/\binto\b/i.test(statement);
}

function normalizeMaxRows(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_MAX_ROWS;
  return Math.min(500, Math.max(1, Math.trunc(value)));
}

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
