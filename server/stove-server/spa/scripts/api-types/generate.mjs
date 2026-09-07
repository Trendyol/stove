import { spawnSync } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import openapiTS, { astToString } from "openapi-typescript";

const server = new URL("../../../", import.meta.url);
const output = new URL("../../src/api/generated/schema.ts", import.meta.url);
// Rust embeds this directory even when only exporting the API on a clean checkout.
await mkdir(new URL("spa/dist/", server), { recursive: true });
const exported = spawnSync("cargo", ["run", "--quiet", "--locked", "--example", "export_openapi"], {
  cwd: server,
  env: { ...process.env, SKIP_SPA_BUILD: "1" },
  encoding: "utf8",
  stdio: ["ignore", "pipe", "inherit"],
});
if (exported.error) throw exported.error;
if (exported.status !== 0) process.exit(exported.status ?? 1);
const schema = JSON.parse(exported.stdout);
const generated =
  "// Generated from the Rust OpenAPI contract. Run npm run generate:api; do not edit.\n\n" +
  astToString(await openapiTS(schema, { alphabetize: true }));
const current = await readFile(output, "utf8").catch((error) => {
  if (error.code === "ENOENT") return undefined;
  throw error;
});
if (process.argv.includes("--check")) {
  if (current !== generated) {
    console.error("API types differ from the Rust OpenAPI contract. Run npm run generate:api.");
    process.exitCode = 1;
  }
} else if (current !== generated) {
  await mkdir(new URL(".", output), { recursive: true });
  await writeFile(output, generated);
}
