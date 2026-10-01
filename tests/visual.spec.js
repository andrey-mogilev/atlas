const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {test, expect} = require("@playwright/test");

const fixture = path.join(__dirname, "fixtures/demo-source");
const sourceRoot = path.join(os.tmpdir(), "atlas-visual-sources");
const sources = [
  path.join(sourceRoot, "atlas-guides"),
  path.join(sourceRoot, "team-playbooks")
];

async function scan(page, source) {
  await page.locator("#target").fill(source);
  await page.locator("#scan-button").click();
  await expect(page.locator("#status")).toHaveText(
    "Scan complete. Results saved on this computer.",
    {timeout: 60_000}
  );
}

async function stabilizeGeneratedValues(page) {
  await page.evaluate(() => {
    const targetHelp = document.querySelector("#target-help");
    if (targetHelp) targetHelp.innerHTML = "GitHub URLs work with or without .git. Relative paths start from <code>/workspace/atlas</code>.";
    document.querySelectorAll(".finding-id").forEach((node, index) => {
      node.textContent = `skl_visual_${String(index + 1).padStart(2, "0")}`;
    });
    document.querySelectorAll(".repository-info").forEach(node => {
      if (node.children[1]) node.children[1].textContent = "local · 1/1/2026, 12:00:00 AM";
    });
    document.querySelectorAll(".history-item").forEach(node => {
      if (node.lastElementChild) node.lastElementChild.textContent = "1/1/2026, 12:00:00 AM";
    });
  });
}

test("core pages match their reviewed visual baselines", async ({page}) => {
  await fs.rm(sourceRoot, {recursive: true, force: true});
  for (const source of sources) await fs.cp(fixture, source, {recursive: true});

  await page.goto("/scans");
  await expect(page.locator("#history")).toContainText("No saved scans yet");
  await stabilizeGeneratedValues(page);
  await expect.soft(page).toHaveScreenshot("scans-empty.png", {fullPage: true});

  for (const source of sources) await scan(page, source);
  await expect(page.locator(".history-group")).toHaveCount(2);
  await stabilizeGeneratedValues(page);
  await expect.soft(page).toHaveScreenshot("scans-populated.png", {fullPage: true});

  await page.locator("#skills-nav").click();
  await expect(page.locator(".repository-row")).toHaveCount(2);
  await expect(page.locator(".finding")).toHaveCount(6);
  await stabilizeGeneratedValues(page);
  await expect.soft(page).toHaveScreenshot("skills-populated.png", {fullPage: true});

  await page.locator("#findings .finding").nth(3).locator(".finding-star").click();
  await page.mouse.move(0, 0);
  await stabilizeGeneratedValues(page);
  await expect.soft(page).toHaveScreenshot("skills-starred.png", {fullPage: true});
  await page.locator("#findings .finding").first().locator(".finding-star").click();
  await page.mouse.move(0, 0);
  await stabilizeGeneratedValues(page);

  await page.setViewportSize({width: 390, height: 844});
  await expect.soft(page.locator("body")).toHaveScreenshot("skills-mobile.png");
});
