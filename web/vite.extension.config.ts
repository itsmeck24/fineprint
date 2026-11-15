import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// chrome content scripts cannot load modules, so each entry is built on its own into one iife file.
// run with --mode content, --mode background, --mode options (see package.json)
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  publicDir: mode === "content" ? "extension/public" : false,
  define: { "process.env.NODE_ENV": JSON.stringify("production") },
  build: {
    outDir: "dist-extension",
    emptyOutDir: mode === "content",
    lib: { entry: `extension/${mode === "background" ? "background.ts" : mode + ".tsx"}`, name: "fineprint", formats: ["iife"], fileName: () => `${mode}.js` },
  },
}));
