// Run after enabling DevTools CLI and starting `cli auto --auto-port 9420`.
// Official test client is isolated under ignored .local/, not a mobile dependency.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const automator = require(
  path.resolve(
    __dirname,
    "../.local/wechat-automation/node_modules/miniprogram-automator",
  ),
);
const output = path.resolve(__dirname, "../.local/wechat-automation/results");
fs.mkdirSync(output, { recursive: true });
const report = {
  checkedAt: new Date().toISOString(),
  scope: "TASK-01 simulator / missing API configuration only",
  checks: [],
  exceptions: [],
  consoleErrors: [],
};
let miniProgram;
const deadline = setTimeout(() => {
  report.status = "FAIL";
  report.failure = "Simulator smoke timed out after 60 seconds";
  fs.writeFileSync(
    path.join(output, "report.json"),
    `${JSON.stringify(report, null, 2)}\n`,
  );
  console.error(report.failure);
  if (miniProgram) miniProgram.disconnect();
  process.exit(1);
}, 60000);
async function main() {
  const endpoint =
    process.env.WECHAT_AUTOMATION_ENDPOINT || "ws://127.0.0.1:9420";
  const parsed = new URL(endpoint);
  assert.equal(parsed.protocol, "ws:");
  assert.ok(
    ["127.0.0.1", "localhost", "[::1]"].includes(parsed.hostname),
    "Automation must stay on loopback",
  );
  miniProgram = await automator.connect({ wsEndpoint: endpoint });
  miniProgram.on("exception", (event) => report.exceptions.push(event));
  miniProgram.on("console", (event) => {
    if (event.level === "error") report.consoleErrors.push(event);
  });
  const page = await miniProgram.reLaunch("/pages/index/index");
  await page.waitFor(".error");
  assert.equal(page.path, "pages/index/index");
  const title = await page.$(".title");
  report.title = await title.text();
  assert.equal(report.title, "企业审批系统 · 工程骨架");
  report.checks.push("Home page and title rendered");
  const error = await page.$(".error");
  report.initialMessage = await error.text();
  assert.match(report.initialMessage, /尚未配置 VITE_API_BASE_URL/);
  report.checks.push(
    "Missing API configuration is visibly explained, not fake UP",
  );
  const button = await page.$("button");
  assert.equal((await button.text()).trim(), "重新检查");
  await button.tap();
  await page.waitFor(".error");
  const repeatedError = await page.$(".error");
  assert.equal(await repeatedError.text(), report.initialMessage);
  report.checks.push(
    "Retry button returns to the expected configuration message",
  );
  report.system = await miniProgram.callWxMethod("getSystemInfoSync");
  await miniProgram.screenshot({ path: path.join(output, "simulator.png") });
  report.checks.push("Simulator screenshot captured");
  assert.deepEqual(
    report.exceptions,
    [],
    "Unexpected simulator runtime exception",
  );
  assert.deepEqual(
    report.consoleErrors,
    [],
    "Unexpected simulator console error",
  );
  report.checks.push(
    "No exception/console error observed during this navigation and tap",
  );
  report.status = "PASS";
}
main()
  .catch((error) => {
    report.status = "FAIL";
    report.failure = error.stack || String(error);
    process.exitCode = 1;
  })
  .finally(() => {
    clearTimeout(deadline);
    fs.writeFileSync(
      path.join(output, "report.json"),
      `${JSON.stringify(report, null, 2)}\n`,
    );
    console.log(JSON.stringify(report, null, 2));
    if (miniProgram) miniProgram.disconnect();
  });
