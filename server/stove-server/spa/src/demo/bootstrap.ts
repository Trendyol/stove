import initSqlJs from "sql.js";
import wasmUrl from "sql.js/dist/sql-wasm.wasm?url";
import { createDemoBackend } from "./backend";

const config = {
  locateFile: () => wasmUrl,
  useOldExtensionFunctions: true,
};

export const demo = initSqlJs(config).then((SQL) => createDemoBackend(SQL, __STOVE_VERSION__));
