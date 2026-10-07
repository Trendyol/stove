import initSqlJs from "sql.js";
import wasmUrl from "sql.js/dist/sql-wasm.wasm?url";
import { createDemoBackend } from "./backend";

export const demo = initSqlJs({ locateFile: () => wasmUrl }).then((SQL) =>
  createDemoBackend(SQL, __STOVE_VERSION__),
);
