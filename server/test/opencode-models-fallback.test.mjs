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
