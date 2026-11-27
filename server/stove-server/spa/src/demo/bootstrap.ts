import initSqlJs from "sql.js";
import wasmUrl from "sql.js/dist/sql-wasm.wasm?url";
import { createDemoBackend } from "./backend";

// sql.js keeps the legacy extension functions by default; its types don't declare the opt-out flag.
export const demo = initSqlJs({ locateFile: () => wasmUrl }).then((SQL) =>
  createDemoBackend(SQL, __STOVE_VERSION__),
);
