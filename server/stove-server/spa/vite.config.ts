import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig } from "vite";

const propsPath = resolve(import.meta.dirname, "../../../gradle.properties");
const props = readFileSync(propsPath, "utf-8");
const releaseVersion = props.match(/^version=(.+)$/m)?.[1]?.trim() ?? "dev";
const snapshotBase = props.match(/^snapshot=(.+)$/m)?.[1]?.trim();
// Mirror build-logic's CI.version so the UI names the same published coordinate
// as the Rust binary and the Kotlin client.
const version =
  process.env.STOVE_VERSION ??
  (process.env.SNAPSHOT === "true" && snapshotBase
    ? `${snapshotBase}.${process.env.BUILD_NUMBER ?? "0"}-SNAPSHOT`
    : releaseVersion);

export default defineConfig(({ mode }) => ({
  base: mode === "demo" ? "/stove/dashboard-demo/" : "./",
  plugins: [react()],
  define: {
    __STOVE_VERSION__: JSON.stringify(version),
    __STOVE_DEMO__: mode === "demo",
  },
  server: {
    proxy: {
      "/api": "http://localhost:4040",
    },
  },
}));
