import { expect, test } from "@playwright/test";

function email() {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`;
}

async function register(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "New here? Create an account" }).click();
  await page.getByLabel("Email").fill(email());
  await page.getByLabel("Password").fill("correct horse battery");
  await page.getByLabel("Graduation month").fill("2027-05");
  await page.getByLabel("Degree").selectOption("master");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "Saved postings" })).toBeVisible();
}

test("save a posting and read its card with quotes", async ({ page }) => {
  await register(page);
  await page.getByLabel("Posting link").fill("https://job-boards.greenhouse.io/demo/jobs/101");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("link", { name: "Software Engineer Intern (Summer 2027)" }).click();

  const grad = page.locator('[data-kind="grad_window"]');
  await expect(grad.locator("[data-label]")).toHaveAttribute("data-label", "conflicts");
  await expect(grad).toContainText("graduating between Fall 2027 and Summer 2028");
  const auth = page.locator('[data-kind="work_auth"]');
  await expect(auth.locator("[data-label]")).toHaveAttribute("data-label", "no_sponsorship");
  await expect(auth).toContainText("the form asks if you need sponsorship");
});

test("changing the profile changes the card", async ({ page }) => {
  await register(page);
  await page.getByLabel("Posting link").fill("https://job-boards.greenhouse.io/demo/jobs/102");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("link", { name: "Backend Engineer, New Grad 2027" }).click();
  await expect(page.locator('[data-kind="grad_window"] [data-label]')).toHaveAttribute("data-label", "matches");

  await page.getByRole("link", { name: "Settings" }).click();
  await page.getByLabel("Graduation month").fill("2027-12");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("link", { name: "Saved" }).click();
  await page.getByRole("link", { name: "Backend Engineer, New Grad 2027" }).click();
  await expect(page.locator('[data-kind="grad_window"] [data-label]')).toHaveAttribute("data-label", "conflicts");
});

test("bad links are rejected with a message", async ({ page }) => {
  await register(page);
  await page.getByLabel("Posting link").fill("https://example.com/careers/123");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("alert")).toContainText("greenhouse");
});

test("deleting the account signs you out for good", async ({ page }) => {
  await register(page);
  await page.getByRole("link", { name: "Settings" }).click();
  page.once("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "Delete my account" }).click();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
});
