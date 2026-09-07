import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api/client";
import type { DatabaseTable } from "../../api/types";
import { adminKeys } from "./admin-queries";
import {
  DEFAULT_MAX_ROWS,
  isReadStatement,
  normalizeMaxRows,
  type QueryTemplate,
  selectSql,
  templateSql,
} from "./database-sql";

export type { QueryTemplate } from "./database-sql";

export function useDatabaseExplorer(onDatabaseChange: () => Promise<void>) {
  const queryClient = useQueryClient();
  const schemaQuery = useQuery({
    queryKey: adminKeys.schema,
    queryFn: ({ signal }) => api.getDatabaseSchema(signal),
  });
  const [tableName, setTableName] = useState<string>();
  const [sqlDraft, setSqlDraft] = useState<string>();
  const [maxRows, setMaxRows] = useState(DEFAULT_MAX_ROWS);
  const [revision, setRevision] = useState(0);
  const tables = schemaQuery.data?.tables ?? [];
  const selectedTable =
    tables.find((table) => table.name === (tableName ?? "runs")) ?? tables[0] ?? null;
  const sql = sqlDraft ?? (selectedTable ? selectSql(selectedTable) : "");
  const execution = useMutation({
    mutationFn: ({ statement, limit }: { statement: string; limit: number; revision: number }) =>
      api.executeDatabaseQuery(statement, limit),
    onSuccess: async (_, { statement }) => {
      if (!isReadStatement(statement)) {
        await queryClient.invalidateQueries({ queryKey: adminKeys.schema });
        await onDatabaseChange();
      }
    },
  });
  const setSql = (statement: string) => {
    setSqlDraft(statement);
    setRevision((current) => current + 1);
  };
  const execute = (statement = sql) => {
    if (!statement.trim() || execution.isPending) return;
    if (
      !isReadStatement(statement) &&
      !confirm("Run this database-changing statement? Direct changes cannot be undone by Stove.")
    )
      return;
    setSqlDraft(statement);
    execution.mutate({ statement, limit: normalizeMaxRows(maxRows), revision });
  };
  const selectTable = (table: DatabaseTable) => {
    setTableName(table.name);
    setSql(selectSql(table));
  };
  const isCurrentResult = execution.variables?.revision === revision;

  return {
    schema: schemaQuery.data ?? null,
    selectedTable,
    selectTable,
    sql,
    setSql,
    maxRows,
    setMaxRows,
    result: isCurrentResult ? (execution.data ?? null) : null,
    busy: execution.isPending,
    error: ((isCurrentResult ? execution.error : null) ?? schemaQuery.error)?.message ?? null,
    execute,
    applyTemplate: (kind: QueryTemplate) => {
      if (selectedTable) setSql(templateSql(kind, selectedTable));
    },
  };
}
