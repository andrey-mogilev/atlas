const os = require("node:os");
const path = require("node:path");
const {defineConfig, devices} = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests",
  outputDir: "./test-results",
  timeout: 120_000,
  fullyParallel: false,
  workers: 1,
  reporter: "line",
  use: {
    baseURL: "http://127.0.0.1:8090",
    viewport: {width: 1440, height: 900},
    video: {mode: "on", size: {width: 1440, height: 900}},
    trace: "retain-on-failure"
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel: "chrome",
        viewport: {width: 1440, height: 900}
      }
    }
  ],
  webServer: {
    command: "mvn -q -DskipTests package && ./bin/skill-atlas serve --port 8090",
    url: "http://127.0.0.1:8090",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      ...process.env,
      SKILL_SCAN_DB_PATH: path.join(os.tmpdir(), "atlas-playwright-demo.sqlite")
    }
  }
});
