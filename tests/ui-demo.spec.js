const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {execFileSync} = require("node:child_process");
const {test, expect} = require("@playwright/test");
const scenario = require("./ui-demo.scenario");

const repositoryRoot = path.resolve(__dirname, "..");
const demoRoot = path.join(os.tmpdir(), "atlas-multi-repository-demo");
const demoSources = [path.join(demoRoot, "atlas-guides"), path.join(demoRoot, "team-playbooks")];
const demoDatabase = path.join(os.tmpdir(), "atlas-playwright-demo.sqlite");

function artifactPrefix() {
  let branch = "detached-head";
  try {
    branch = execFileSync("git", ["branch", "--show-current"], {
      cwd: repositoryRoot,
      encoding: "utf8"
    }).trim() || branch;
  } catch {
    // Keep the stable fallback when Git metadata is unavailable.
  }
  const mangledBranch = branch.toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "detached-head";
  const date = new Date().toISOString().slice(0, 10);
  return `${mangledBranch}-${date}`;
}

async function captureMoment(page, demos, prefix, moment) {
  const name = scenario.screenshots[moment];
  if (!name) return;
  await page.screenshot({
    path: path.join(demos, `${prefix}-${name}.png`),
    animations: "disabled"
  });
}

async function installRecordingStyles(context, page) {
  const cdp = await context.newCDPSession(page);
  await cdp.send("DOM.enable");
  await cdp.send("CSS.enable");
  const {frameTree} = await cdp.send("Page.getFrameTree");
  const {styleSheetId} = await cdp.send("CSS.createStyleSheet", {
    frameId: frameTree.frame.id
  });
  await cdp.send("CSS.setStyleSheetText", {
    styleSheetId,
    text: `
      html { scroll-behavior: smooth !important; }
      .demo-focus {
        outline: 4px solid #e09f3e !important;
        outline-offset: 5px !important;
        border-radius: 10px !important;
        box-shadow: 0 0 0 10px rgba(224, 159, 62, .20),
                    0 10px 28px rgba(48, 75, 62, .18) !important;
        position: relative !important;
        z-index: 20 !important;
      }
      .demo-cursor {
        position: fixed;
        top: 0;
        left: 0;
        width: 28px;
        height: 34px;
        background: #e09f3e;
        clip-path: polygon(0 0, 0 100%, 31% 73%, 49% 100%, 64% 91%, 46% 64%, 100% 61%);
        filter: drop-shadow(0 2px 1px #fff) drop-shadow(0 3px 4px rgba(20, 45, 34, .55));
        pointer-events: none;
        z-index: 2147483647;
      }
      .demo-click {
        position: fixed;
        top: 0;
        left: 0;
        width: 34px;
        height: 34px;
        border: 4px solid #e09f3e;
        border-radius: 50%;
        opacity: 0;
        pointer-events: none;
        z-index: 2147483646;
      }
    `
  });
  await page.evaluate(() => {
    const cursor = document.createElement("div");
    cursor.className = "demo-cursor";
    cursor.dataset.x = "1240";
    cursor.dataset.y = "80";
    document.body.append(cursor);
    const ripple = document.createElement("div");
    ripple.className = "demo-click";
    document.body.append(ripple);
    cursor.animate(
      [{transform: "translate(1240px, 80px)"}, {transform: "translate(1240px, 80px)"}],
      {duration: 1, fill: "forwards"}
    );
  });
}

async function moveCursor(page, x, y) {
  await page.evaluate(async ({x, y}) => {
    const cursor = document.querySelector(".demo-cursor");
    const fromX = Number(cursor.dataset.x);
    const fromY = Number(cursor.dataset.y);
    const animation = cursor.animate(
      [
        {transform: `translate(${fromX}px, ${fromY}px)`},
        {transform: `translate(${x}px, ${y}px)`}
      ],
      {duration: 700, easing: "cubic-bezier(.22, .61, .36, 1)", fill: "forwards"}
    );
    cursor.dataset.x = String(x);
    cursor.dataset.y = String(y);
    await animation.finished;
  }, {x, y});
}

async function clickRipple(page, x, y) {
  await page.evaluate(async ({x, y}) => {
    const ripple = document.querySelector(".demo-click");
    const animation = ripple.animate(
      [
        {transform: `translate(${x - 17}px, ${y - 17}px) scale(.35)`, opacity: .95},
        {transform: `translate(${x - 17}px, ${y - 17}px) scale(1.45)`, opacity: 0}
      ],
      {duration: 520, easing: "ease-out"}
    );
    await animation.finished;
  }, {x, y});
}

async function focus(page, selector, hold = 750) {
  const item = (typeof selector === "string" ? page.locator(selector) : selector).first();
  await item.scrollIntoViewIfNeeded();
  await page.waitForTimeout(450);
  await page.locator(".demo-focus").evaluateAll(nodes => {
    nodes.forEach(node => node.classList.remove("demo-focus"));
  });
  await item.evaluate(node => node.classList.add("demo-focus"));
  const box = await item.boundingBox();
  const x = box.x + Math.min(box.width * .72, box.width - 18);
  const y = box.y + box.height * .55;
  await moveCursor(page, x, y);
  await page.waitForTimeout(hold);
  return {item, x, y};
}

async function click(page, target) {
  await clickRipple(page, target.x, target.y);
  await target.item.click();
}

test(`records the ${scenario.name}`, async ({page, context}) => {
  const demos = "/Users/andrey.mogilev/Projects/Videos";
  const prefix = artifactPrefix();
  await fs.mkdir(demos, {recursive: true});
  await fs.rm(demoRoot, {recursive: true, force: true});
  await fs.rm(demoDatabase, {force: true});
  for (const source of demoSources) await fs.cp(path.join(__dirname, scenario.sourceFixture), source, {recursive: true});
  await page.goto("/scans");
  await installRecordingStyles(context, page);
  await page.waitForTimeout(1_400);

  for (const source of demoSources) {
    let target = await focus(page, "#target", 450);
    await click(page, target);
    await target.item.pressSequentially(source, {delay: 80});
    await page.waitForTimeout(700);
    target = await focus(page, "#scan-button", 550);
    await click(page, target);
    await expect(page.locator("#status")).toHaveText("Scan complete. Results saved on this computer.", {timeout: 60_000});
    await page.locator("#target").fill("");
  }

  let target = await focus(page, "#skills-nav", 700);
  await click(page, target);
  await installRecordingStyles(context, page);
  await expect(page.locator(".repository-row")).toHaveCount(2);
  await expect(page.locator(".finding")).toHaveCount(6);
  await focus(page, "#repository-selector", 1_500);
  await captureMoment(page, demos, prefix, "repositoriesLoaded");

  target = await focus(page, ".repository-row input", 700);
  await click(page, target);
  await expect(page.locator("#all-repositories")).not.toBeChecked();
  await expect(page.locator(".finding")).toHaveCount(3);
  await page.waitForTimeout(1_400);
  await captureMoment(page, demos, prefix, "partialSelection");
  await click(page, target);
  await expect(page.locator(".finding")).toHaveCount(6);
  await page.waitForTimeout(800);

  const lastCard = page.locator("#findings .finding").last();
  const lastName = await lastCard.locator(".finding-name").textContent();
  target = await focus(page, lastCard.locator(".finding-star"), 900);
  await click(page, target);
  const firstStar = page.locator("#findings .finding").first().locator(".finding-star");
  await expect(firstStar).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator("#findings .finding").first().locator(".finding-name")).toHaveText(lastName);
  await focus(page, "#findings .finding", 1_900);
  await captureMoment(page, demos, prefix, "skillStarred");
  await page.waitForTimeout(700);

  target = await focus(page, "#skill-filter", 650);
  await click(page, target);
  await target.item.pressSequentially(scenario.filter, {delay: 115});
  await expect(page.locator("#filter-summary")).toHaveText(scenario.expectedFilterSummary);
  await page.waitForTimeout(1_800);
  await captureMoment(page, demos, prefix, "filterApplied");
  await target.item.press(process.platform === "darwin" ? "Meta+A" : "Control+A");
  await target.item.press("Backspace");
  await page.waitForTimeout(900);

  target = await focus(page, ".finding-select", 900);
  await click(page, target);
  await expect(page.locator("#similarity-panel")).toBeVisible();
  await expect(page.locator(".similarity-repository", {hasText: "team-playbooks"}).first()).toBeVisible();
  await focus(page, "#similarity-panel", 2_200);
  await captureMoment(page, demos, prefix, "relatedOpened");
  await page.locator(".demo-focus").evaluateAll(nodes => {
    nodes.forEach(node => node.classList.remove("demo-focus"));
  });
  await page.waitForTimeout(800);

  const video = page.video();
  await page.close();
  await video.saveAs(path.join(demos, `${prefix}.webm`));
});
