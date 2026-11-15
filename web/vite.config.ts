import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// in dev the api runs on 8080, the app on 8081 (the port the alert emails link to)
export default defineConfig({
  plugins: [react()],
  server: { port: 8081, proxy: { "/api": "http://localhost:8080" } },
  preview: { port: 8081, proxy: { "/api": "http://localhost:8080" } },
});
