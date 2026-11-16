import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// the project site for github pages: builds web/site into docs/ at the repo root
export default defineConfig({
  root: "site",
  base: "./",
  plugins: [react()],
  build: { outDir: "../../docs", emptyOutDir: true },
});
