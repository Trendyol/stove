import { Fragment } from "react";
import type { DatabaseQueryResult } from "../../api/types";
import { type QueryTemplate, useDatabaseExplorer } from "./useDatabaseExplorer";

const TEMPLATES: Array<{ kind: QueryTemplate; label: string }> = [
  { kind: "select", label: "Select" },
  { kind: "insert", label: "Insert" },
  { kind: "update", label: "Update" },
  { kind: "delete", label: "Delete" },
];

export function DatabaseExplorer({ onDatabaseChange }: { onDatabaseChange: () => Promise<void> }) {
  const explorer = useDatabaseExplorer(onDatabaseChange);

  return (
    <section className="stove-admin-card stove-database-explorer">
      <div className="stove-database-header">
        <div>
          <h3>Database explorer</h3>
          <p>
            Inspect tables and execute one SQL statement against Stove’s active database. Direct
            writes can bypass Stove’s safeguards.
          </p>
        </div>
        <span>{explorer.schema?.backend ?? "Loading…"}</span>
      </div>
      <div className="stove-database-layout">
        <aside className="stove-database-schema" aria-label="Database tables">
          {explorer.schema?.tables.map((table) => (
            <Fragment key={table.name}>
              <button
                type="button"
                className={explorer.selectedTable?.name === table.name ? "is-selected" : ""}
                onClick={() => explorer.selectTable(table)}
              >
                {table.name}
              </button>
              {explorer.selectedTable?.name === table.name ? (
                <ul>
                  {table.columns.map((column) => (
                    <li key={column.name}>
                      <code>{column.name}</code>
                      <span>
                        {column.data_type}
                        {column.primary_key ? " · PK" : ""}
                      </span>
                    </li>
                  ))}
                </ul>
              ) : null}
            </Fragment>
          ))}
        </aside>
        <div className="stove-database-workbench">
          <div className="stove-database-templates">
            {TEMPLATES.map((template) => (
              <button
                key={template.kind}
                type="button"
                disabled={!explorer.selectedTable}
                onClick={() => explorer.applyTemplate(template.kind)}
              >
                {template.label}
              </button>
            ))}
          </div>
          <label className="stove-admin-field">
            <span>SQL statement</span>
            <textarea
              spellCheck={false}
              value={explorer.sql}
              onChange={(event) => explorer.setSql(event.target.value)}
            />
          </label>
          <div className="stove-database-runner">
            <label className="stove-admin-field">
              <span>Maximum rows</span>
              <input
                min="1"
                max="500"
                type="number"
                value={explorer.maxRows}
                onChange={(event) => explorer.setMaxRows(Number(event.target.value))}
              />
            </label>
            <button
              type="button"
              disabled={explorer.busy || !explorer.sql.trim()}
              onClick={() => void explorer.execute()}
            >
              {explorer.busy ? "Running…" : "Run statement"}
            </button>
          </div>
          {explorer.error ? <div className="stove-admin-error">{explorer.error}</div> : null}
          {explorer.result ? <QueryResult result={explorer.result} /> : null}
        </div>
      </div>
    </section>
  );
}

function QueryResult({ result }: { result: DatabaseQueryResult }) {
  if (result.columns.length === 0) {
    return <div className="stove-database-summary">{result.affected_rows} row(s) affected</div>;
  }

  const columns = uniqueKeys(result.columns);
  const rows = uniqueKeys(result.rows.map((row) => JSON.stringify(row))).map((item, index) => ({
    key: item.key,
    row: result.rows[index],
  }));

  return (
    <div className="stove-database-results">
      <div className="stove-database-summary">
        {result.rows.length} row(s)
        {result.truncated ? " · result truncated" : ""}
      </div>
      <div className="stove-database-table-scroll">
        <table>
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.key}>{column.value}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map(({ key, row }) => (
              <tr key={key}>
                {row.map((value, columnIndex) => (
                  <td key={columns[columnIndex]?.key}>
                    {value ?? <span className="is-null">NULL</span>}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function uniqueKeys<T extends string>(values: T[]): Array<{ key: string; value: T }> {
  const occurrences = new Map<string, number>();
  return values.map((value) => {
    const occurrence = occurrences.get(value) ?? 0;
    occurrences.set(value, occurrence + 1);
    return { key: `${value}:${occurrence}`, value };
  });
}
