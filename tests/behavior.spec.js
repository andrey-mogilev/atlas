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

// An organization scan can save far more repositories than the results endpoint
// accepts in one request, so the Skills page has to split its selection.
test("the Skills page loads a selection larger than one results request allows", async ({page}) => {
  const count = 101;
  const repositories = Array.from({length: count}, (_, index) => ({
    id: index + 1,
    canonicalUrl: `https://github.com/acme/repo-${index + 1}`,
    label: `acme/repo-${index + 1}`,
    latestScanId: index + 1,
    target: `https://github.com/acme/repo-${index + 1}`,
    branch: "main",
    commit: "a".repeat(40),
    scannedAt: "2026-01-01T00:00:00Z",
    skillCount: 1,
    locationCount: 1
  }));
  const batchSizes = [];

  await page.route("**/api/repositories", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(repositories)
  }));
  await page.route("**/api/repository-results*", route => {
    const ids = new URL(route.request().url()).searchParams.get("ids").split(",").map(Number);
    batchSizes.push(ids.length);
    // The real endpoint rejects more than 100 ids; the stub has to as well.
    if (ids.length > 100) {
      return route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({message: "Invalid repository selection"})
      });
    }
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        repositories: ids.map(id => {
          const repository = repositories[id - 1];
          const link = `${repository.canonicalUrl}/blob/main/skills/one/SKILL.md`;
          return {
            ...repository,
            result: {
              target: repository.target,
              branch: "main",
              commit: repository.commit,
              text: "",
              locationCount: 1,
              findings: [{
                id: `skl_${id}`,
                name: `Skill ${id}`,
                path: "skills/one/SKILL.md",
                link,
                description: `Skill ${id}.`,
                locations: [{id: `skl_${id}`, path: "skills/one/SKILL.md", link}]
              }]
            }
          };
        })
      })
    });
  });

  await page.goto("/");
  await expect(page.locator(".repository-row")).toHaveCount(count);
  await expect(page.locator("#findings .finding")).toHaveCount(count);
  await expect(page.locator("#repositories-error")).toBeHidden();
  expect(batchSizes).toEqual([100, 1]);
  expect(await visibleNames(page)).toContain("Skill 101");
});
