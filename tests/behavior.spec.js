const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {test, expect} = require("@playwright/test");

const fixture = path.join(__dirname, "fixtures/demo-source");
const source = path.join(os.tmpdir(), "atlas-behavior-source");
const duplicate = path.join(source, "skills/release-planning-copy/SKILL.md");
const unstarred = ["Accessibility review", "Release notes", "Release planning"];

function visibleNames(page) {
  return page.locator("#findings .finding-name").allTextContents();
}

async function scan(page, target) {
  await page.locator("#target").fill(target);
  await page.locator("#scan-button").click();
  await expect(page.locator("#status")).toHaveText(
    "Scan complete. Results saved on this computer.",
    {timeout: 60_000}
  );
}

test("starred skills lead the skill list, survive filtering, and persist", async ({page}) => {
  await fs.rm(source, {recursive: true, force: true});
  await fs.cp(fixture, source, {recursive: true});
  await fs.mkdir(path.dirname(duplicate), {recursive: true});
  await fs.copyFile(path.join(source, "skills/release-planning/SKILL.md"), duplicate);

  await page.goto("/scans");
  await scan(page, source);

  await page.locator("#skills-nav").click();
  await expect(page.locator("#findings .finding")).toHaveCount(3);
  expect(await visibleNames(page)).toEqual(unstarred);
  await expect(page.locator(".finding-star[aria-pressed='true']")).toHaveCount(0);

  // Starring the last skill moves it to the front without changing the rest of the order.
  await page.locator("#findings .finding").nth(2).locator(".finding-star").click();
  expect(await visibleNames(page)).toEqual(["Release planning", "Accessibility review", "Release notes"]);
  const starred = page.locator("#findings .finding").first().locator(".finding-star");
  await expect(starred).toHaveAttribute("aria-pressed", "true");
  await expect(starred).toHaveAccessibleName(/^Unstar Release planning in /);

  // A filter hides non-matching skills and still lists the starred match first.
  await page.locator("#skill-filter").fill("release");
  expect(await visibleNames(page)).toEqual(["Release planning", "Release notes"]);
  await expect(page.locator("#filter-summary")).toHaveText("Showing 2 of 3 skills.");

  await page.locator("#skill-filter").fill("");
  expect(await visibleNames(page)).toEqual(["Release planning", "Accessibility review", "Release notes"]);

  // The star is browser state, so it survives a reload without rescanning.
  await page.reload();
  await expect(page.locator("#findings .finding")).toHaveCount(3);
  expect(await visibleNames(page)).toEqual(["Release planning", "Accessibility review", "Release notes"]);

  // A duplicate group remains starred when the representative copy is removed by a later scan.
  await fs.rm(path.dirname(duplicate), {recursive: true});
  await page.locator("#scans-nav").click();
  await scan(page, source);
  await page.locator("#skills-nav").click();
  await expect(page.locator("#findings .finding")).toHaveCount(3);
  expect(await visibleNames(page)).toEqual(["Release planning", "Accessibility review", "Release notes"]);
  await expect(page.locator("#findings .finding").first().locator(".finding-star")).toHaveAttribute("aria-pressed", "true");

  // Unstarring from the keyboard restores the original order and keeps focus on the same skill.
  await page.locator("#findings .finding").first().locator(".finding-star").focus();
  await page.keyboard.press("Enter");
  expect(await visibleNames(page)).toEqual(unstarred);
  await expect(page.locator(".finding-star[aria-pressed='true']")).toHaveCount(0);
  await expect(page.locator("#findings .finding").nth(2).locator(".finding-star")).toBeFocused();

  // Inspecting a saved scan keeps the scan's own order and offers no star control.
  await page.locator("#scans-nav").click();
  await page.locator(".history-item").first().click();
  await expect(page.locator("#detail-findings .finding")).toHaveCount(3);
  await expect(page.locator("#detail-findings .finding-star")).toHaveCount(0);
});
