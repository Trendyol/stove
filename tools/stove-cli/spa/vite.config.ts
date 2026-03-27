import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "fs";

const gradleProps = readFileSync("../../../gradle.properties", "utf-8");
const version = gradleProps.match(/^version=(.+)$/m)?.[1] ?? "dev";

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
