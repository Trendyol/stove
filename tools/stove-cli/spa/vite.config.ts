import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig } from "vite";

const propsPath = resolve(import.meta.dirname, "../../../gradle.properties");
const version = readFileSync(propsPath, "utf-8").match(/^version=(.+)$/m)?.[1] ?? "dev";

export default defineConfig({
  plugins: [react()],
  define: {
    __STOVE_VERSION__: JSON.stringify(version),
  },
  server: {
    proxy: {
      "/api": "http://localhost:4040",
    },
  },
});
