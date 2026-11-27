import { test } from "node:test";
import assert from "node:assert/strict";
import { mapOpencodeModels, rowsFromConfigProviders } from "../dist/opencode-proxy.js";

test("config/providers rows map to provider/model refs", () => {
  const rows = rowsFromConfigProviders({
    providers: [
      { id: "opencode", models: { "big-pickle": { id: "big-pickle", name: "Big Pickle" } } },
      { id: "opencode-go", models: { "gpt-5.6-luna": { name: "Luna" } } },
      { id: 3, models: {} },
    ],
  });
  assert.deepEqual(mapOpencodeModels(rows).map((m) => [m.id, m.label]), [
    ["opencode-go/gpt-5.6-luna", "Luna"],
    ["opencode/big-pickle", "Big Pickle"],
  ]);
  assert.deepEqual(rowsFromConfigProviders(null), []);
});

test("models that may train on data are refused unless allowed", async () => {
  const { trainsOnData } = await import("../dist/opencode-proxy.js");
  assert.equal(trainsOnData("opencode-go/muse-spark-1.3-contributor", {}), true);
  assert.equal(trainsOnData("opencode/big-pickle", {}), true);
  assert.equal(trainsOnData("opencode-go/gpt-5.6-luna", {}), false);
  assert.equal(trainsOnData("opencode/big-pickle", { TERMINUS_OPENCODE_ALLOW_TRAINING: "1" }), false);
});

test("Claude turns run with telemetry, error reports and feedback uploads off", async () => {
  const { claudeEnv } = await import("../dist/backends/claude.js");
  const env = claudeEnv();
  for (const k of ["DISABLE_TELEMETRY", "DISABLE_ERROR_REPORTING", "DISABLE_FEEDBACK_COMMAND", "CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"]) assert.equal(env[k], "1", k);
});
