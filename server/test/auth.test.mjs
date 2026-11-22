/** Tests for the single auth authority (pure, no servers).
 *  Run: npm test (builds first, then node --test)
 *
 *  Contract: one token (HEADLESS_TOKEN or file). Header bearer and guarded
 *  cookie everywhere; ?token= only for non-/api GET media links, never for
 *  /api/*. Malformed cookies fail closed, never throw.
 */
import { describe, it } from "node:test";
import assert from "node:assert/strict";

process.env.HEADLESS_TOKEN = "test-token-abc";
const { authorized, bearerMatches, UPLOAD_TOKEN } = await import("../dist/auth.js");

const req = (headers = {}, url = "/", method = "GET") => ({ headers, url, method });

describe("auth authority", () => {
  it("matches the configured token only", () => {
    assert.equal(bearerMatches("test-token-abc"), true);
    assert.equal(bearerMatches("wrong"), false);
    assert.equal(bearerMatches(""), false);
    assert.equal(bearerMatches("test-token-abc "), false);
  });

  it("accepts header bearer on api", () => {
    assert.equal(
      authorized(req({ authorization: "Bearer test-token-abc" }, "/api/turn?chatId=c", "GET")),
      true,
    );
    assert.equal(
      authorized(req({ authorization: "Bearer wrong" }, "/api/turn?chatId=c", "GET")),
      false,
    );
  });

  it("never accepts query tokens on /api/*", () => {
    assert.equal(authorized(req({}, "/api/turn?chatId=c&token=test-token-abc", "GET")), false);
    assert.equal(authorized(req({}, "/api/stream?chatId=c&token=test-token-abc", "GET")), false);
    assert.equal(authorized(req({}, "/api/sessions?token=test-token-abc", "GET")), false);
  });

  it("still accepts query tokens for gallery/media links", () => {
    assert.equal(authorized(req({}, "/terminus/2026-09-30/x.apk?token=test-token-abc", "GET")), true);
  });

  it("malformed cookie fails closed without throwing", () => {
    assert.equal(authorized(req({ cookie: "headless-token=%E0%A4%A" }, "/api/sessions", "GET")), false);
    assert.equal(
      authorized(req({ cookie: "headless-token=test-token-abc" }, "/api/sessions", "GET")),
      true,
    );
  });

  it("upload key stays separate from the bearer", () => {
    assert.ok(UPLOAD_TOKEN && !UPLOAD_TOKEN.includes("test-token-abc"));
  });
});
