// Replaced at build time. The regular dashboard excludes demo data and SQLite WASM.
export const isDemo = typeof __STOVE_DEMO__ !== "undefined" && __STOVE_DEMO__;
