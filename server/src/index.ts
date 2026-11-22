#!/usr/bin/env node
import { chromium } from "playwright";

const args = process.argv.slice(2);
if (args[0] === "open" && args[1]) {
  const url = args[1];
  const shotIdx = args.indexOf("--screenshot");
  const shot = shotIdx !== -1 ? args[shotIdx + 1] : "/tmp/headless.png";
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(url, { waitUntil: "networkidle" });
  await page.screenshot({ path: shot, fullPage: true });
  console.log(`shot ${shot} for ${url}`);
  await browser.close();
  process.exit(0);
}
console.log("server <command>\n  open <url> --screenshot <path>");
