const os = require("node:os");
const path = require("node:path");
const {defineConfig, devices} = require("@playwright/test");

const runDirectory = path.join(os.tmpdir(), `atlas-behavior-${process.pid}`);

module.exports = defineConfig({
  testDir: "./tests",
  testMatch: "behavior.spec.js",
  outputDir: "./target/behavior-results",
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  reporter: "line",
  use: {
    baseURL: "http://127.0.0.1:8092",
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
    command: "mvn -q -DskipTests package && ./bin/skill-atlas serve --port 8092",
    url: "http://127.0.0.1:8092",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      ...process.env,
      SKILL_SCAN_DB_PATH: path.join(runDirectory, "atlas.sqlite")
    }
  }
});
