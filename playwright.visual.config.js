const os = require("node:os");
const path = require("node:path");
const {defineConfig, devices} = require("@playwright/test");

const runDirectory = path.join(os.tmpdir(), `atlas-visual-${process.pid}`);

module.exports = defineConfig({
  testDir: "./tests",
  testMatch: "visual.spec.js",
  outputDir: "./target/visual-results",
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  reporter: "line",
  expect: {
    toHaveScreenshot: {
      animations: "disabled",
      maxDiffPixelRatio: 0.005
    }
  },
  use: {
    baseURL: "http://127.0.0.1:8091",
    locale: "en-US",
    timezoneId: "UTC",
    colorScheme: "light",
    reducedMotion: "reduce",
    trace: "retain-on-failure"
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: {width: 1440, height: 900}
      }
    }
  ],
  webServer: {
    command: "mvn -q -DskipTests package && ./bin/skill-atlas serve --port 8091",
    url: "http://127.0.0.1:8091",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      ...process.env,
      SKILL_SCAN_DB_PATH: path.join(runDirectory, "atlas.sqlite")
    }
  }
});
