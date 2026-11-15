import { chromium, expect, test } from "@playwright/test";
import path from "node:path";
import os from "node:os";
import fs from "node:fs";

// loads the built extension into chromium. the greenhouse page itself is a stand-in served by playwright,
// so nothing is fetched from the real site; the extension talks to the local api.
test("extension shows the card on a greenhouse job page", async () => {
  const ext = path.resolve(import.meta.dirname, "..", "dist-extension");
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "fp-ext-"));
  const ctx = await chromium.launchPersistentContext(profile, {
    channel: "chromium",
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
  });
  try {
    let [worker] = ctx.serviceWorkers();
    if (!worker) worker = await ctx.waitForEvent("serviceworker");
    const extId = new URL(worker.url()).host;

    // account through the api, sign in through the extension options page
    const user = `ext-${Date.now()}@example.test`;
    const api = process.env.FINEPRINT_API ?? "http://localhost:8080";
    const reg = await fetch(`${api}/api/auth/register`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: user, password: "correct horse battery", gradMonth: "2027-05", degreeLevel: "master" }),
    });
    expect(reg.status).toBe(201);
    const options = await ctx.newPage();
    await options.goto(`chrome-extension://${extId}/options.html`);
    await options.getByLabel("API address").fill(api);
    await options.getByLabel("Email").fill(user);
    await options.getByLabel("Password").fill("correct horse battery");
    await options.getByRole("button", { name: "Sign in" }).click();
    await expect(options.getByText("Signed in.")).toBeVisible();

    const page = await ctx.newPage();
    await page.route("https://job-boards.greenhouse.io/**", (r) =>
      r.fulfill({ contentType: "text/html", body: "<html><body><h1>Software Engineer Intern</h1></body></html>" }));
    await page.goto("https://job-boards.greenhouse.io/demo/jobs/101");
    const panel = page.locator("#fineprint-root");
    await panel.getByRole("button", { name: "Save to FinePrint" }).click();
    await expect(panel.locator('[data-kind="grad_window"] [data-label]')).toHaveAttribute("data-label", "conflicts", { timeout: 20_000 });
    await expect(panel.locator('[data-kind="grad_window"]')).toContainText("graduating between Fall 2027 and Summer 2028");
  } finally {
    await ctx.close();
  }
});
