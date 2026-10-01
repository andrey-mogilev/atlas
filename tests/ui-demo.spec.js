const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const {execFileSync} = require("node:child_process");
const {test, expect} = require("@playwright/test");
const scenario = require("./ui-demo.scenario");

const repositoryRoot = path.resolve(__dirname, "..");
const demos = path.join(repositoryRoot, "demos");
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

/**
 * A modal dialog renders in the browser's top layer, above every z-index, so
 * the recording cursor has to live inside the dialog while it is open.
 */
async function hostOverlays(page, selector) {
  await page.evaluate(selector => {
    const host = selector ? document.querySelector(selector) : document.body;
    const cursor = document.querySelector(".demo-cursor");
    const ripple = document.querySelector(".demo-click");
    host.append(cursor, ripple);
    // Re-parenting drops the running animation that holds the cursor in place.
    cursor.animate(
      [{transform: `translate(${cursor.dataset.x}px, ${cursor.dataset.y}px)`}],
      {duration: 1, fill: "forwards"}
    );
  }, selector);
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
  const item = page.locator(selector).first();
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
  const prefix = artifactPrefix();
  await fs.mkdir(demos, {recursive: true});
  await fs.rm(demoDatabase, {force: true});
  await page.goto("/scans");
  await installRecordingStyles(context, page);
  await page.waitForTimeout(1_400);

  // A GitHub URL with only an account name is now recognised as an owner.
  let target = await focus(page, "#target", 450);
  await click(page, target);
  await target.item.pressSequentially(scenario.owner, {delay: 90});
  await page.waitForTimeout(800);
  target = await focus(page, "#scan-button", 550);
  await click(page, target);

  const dialog = page.locator("#owner-dialog");
  await expect(dialog).toBeVisible();
  await hostOverlays(page, "#owner-dialog");
  await focus(page, "#owner-dialog-text", 1_700);
  await captureMoment(page, demos, prefix, "confirmation");
  const announced = await page.locator("#owner-dialog-text").textContent();
  const repositoryCount = Number(/owns (\d+) repositor/.exec(announced)[1]);
  expect(repositoryCount).toBeGreaterThan(0);
  await expect(page.locator("#owner-dialog-known")).toHaveText("None of them have saved results yet.");
  await focus(page, "#owner-dialog-known", 1_200);
  await focus(page, ".owner-rescan-check", 1_300);
  await expect(page.locator("#owner-rescan")).not.toBeChecked();
  target = await focus(page, "#owner-start", 600);
  await click(page, target);
  await expect(dialog).toBeHidden();
  await hostOverlays(page, null);

  // Every repository is reported as it finishes, behind a determinate bar.
  await expect(page.locator("#owner-progress")).toBeVisible();
  await expect(page.locator(".owner-repository").first()).toBeVisible({timeout: 300_000});
  await captureMoment(page, demos, prefix, "progress");
  await focus(page, "#owner-progress", 900);
  await expect(page.locator("#status")).toHaveText(
    new RegExp(`Organization scan complete: ${repositoryCount} scanned, 0 already scanned`),
    {timeout: 300_000}
  );
  await expect(page.locator(".owner-repository")).toHaveCount(repositoryCount);
  await focus(page, "#owner-progress", 2_000);
  await captureMoment(page, demos, prefix, "completed");
  await expect(page.locator(".history-group")).toHaveCount(repositoryCount);

  // Submitting the same account again reports the saved results instead of
  // scanning, because the rescan checkbox is cleared by default.
  target = await focus(page, "#scan-button", 700);
  await click(page, target);
  await expect(dialog).toBeVisible();
  await hostOverlays(page, "#owner-dialog");
  await expect(page.locator("#owner-dialog-known")).toHaveText(
    `${repositoryCount} of them already have saved results.`
  );
  await focus(page, "#owner-dialog-known", 1_600);
  await captureMoment(page, demos, prefix, "alreadyScanned");
  await expect(page.locator("#owner-rescan")).not.toBeChecked();
  target = await focus(page, "#owner-start", 600);
  await click(page, target);
  await expect(dialog).toBeHidden();
  await hostOverlays(page, null);
  await expect(page.locator("#status")).toHaveText(
    new RegExp(`Organization scan complete: 0 scanned, ${repositoryCount} already scanned`),
    {timeout: 300_000}
  );
  await focus(page, "#owner-progress", 1_700);
  await captureMoment(page, demos, prefix, "skipped");

  // Ticking the checkbox scans the same repositories again.
  target = await focus(page, "#scan-button", 650);
  await click(page, target);
  await expect(dialog).toBeVisible();
  await hostOverlays(page, "#owner-dialog");
  target = await focus(page, ".owner-rescan-check", 900);
  await click(page, target);
  await expect(page.locator("#owner-rescan")).toBeChecked();
  await page.waitForTimeout(1_100);
  target = await focus(page, "#owner-start", 600);
  await click(page, target);
  await expect(dialog).toBeHidden();
  await hostOverlays(page, null);
  await expect(page.locator("#status")).toHaveText(
    new RegExp(`Organization scan complete: ${repositoryCount} scanned, 0 already scanned`),
    {timeout: 300_000}
  );
  await focus(page, "#owner-progress", 1_800);
  await captureMoment(page, demos, prefix, "rescanned");

  // The skills the owner scan saved are available on the Skills page.
  target = await focus(page, "#skills-nav", 700);
  await click(page, target);
  await installRecordingStyles(context, page);
  await expect(page.locator(".repository-row")).toHaveCount(repositoryCount);
  await expect(page.locator(".finding").first()).toBeVisible();
  await focus(page, "#repository-selector", 2_200);
  await captureMoment(page, demos, prefix, "skills");
  await page.locator(".demo-focus").evaluateAll(nodes => {
    nodes.forEach(node => node.classList.remove("demo-focus"));
  });
  await page.waitForTimeout(900);

  const video = page.video();
  await page.close();
  await video.saveAs(path.join(demos, `${prefix}.webm`));
});
