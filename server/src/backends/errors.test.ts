import test from "node:test";
import assert from "node:assert/strict";
import { classifyProviderError } from "./errors.js";
import { claudeError } from "./claude.js";
import { codexError } from "./codex.js";
import { agyError } from "./agy.js";

test("classifyProviderError: first matching rule wins, else provider_error", () => {
  const rules = [
    { re: /a/, code: "usage_limit", message: "A" },
    { re: /b/, code: "rate_limited", message: "B", retryable: true },
  ] as const;
  const hit = classifyProviderError(new Error("AB"), "op", "claude", "fallback", rules);
  assert.deepEqual([hit.code, hit.message, hit.retryable, hit.backend, hit.operation], ["usage_limit", "A", false, "claude", "op"]);
  const miss = classifyProviderError("zzz", "op", "codex", "fallback", rules);
  assert.deepEqual([miss.code, miss.message, miss.retryable], ["provider_error", "fallback", false]);
});

test("each backend keeps its own rule order", () => {
  // "closed ... session not found": Claude checks session_not_found first, Codex checks unavailable first.
  assert.equal(claudeError("session not found, stream closed", "x").code, "session_not_found");
  assert.equal(codexError("thread not found, stream closed", "x").code, "backend_unavailable");
  assert.equal(agyError("unknown model foo", "x").code, "model_not_found");
  assert.equal(claudeError("billing issue", "x").code, "not_authenticated");
  assert.equal(codexError("billing issue", "x").code, "provider_error");
});
