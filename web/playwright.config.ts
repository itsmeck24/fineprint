import { defineConfig } from "@playwright/test";

// runs against the e2e compose stack (fake job board, real api, worker, postgres and web app):
//   FINEPRINT_DB_PORT=15434 FINEPRINT_API_PORT=18080 FINEPRINT_WEB_PORT=18081 FINEPRINT_MAIL_PORT=18025 \
//     docker compose -p fineprint-e2e -f docker-compose.yml -f docker-compose.e2e.yml up -d --build
//   FINEPRINT_WEB=http://localhost:18081 FINEPRINT_API=http://localhost:18080
//   cd web && npx playwright test
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: 0,
  use: { baseURL: process.env.FINEPRINT_WEB ?? "http://localhost:8081", trace: "retain-on-failure" },
});
