import { test } from "node:test";
import assert from "node:assert/strict";
import { apiRoutes, lateRoutes, dispatch, routeMatches } from "../dist/routes.js";

const ctx = (method, path) => ({ req: { method }, res: {}, path });
const route = (method, path, handle, extra = {}) => ({ method, path, handle, ...extra });

test("an exact route matches its method and path only", () => {
  const r = route("GET", "/api/x", () => {});
  assert.equal(routeMatches(r, "GET", "/api/x"), true);
  assert.equal(routeMatches(r, "POST", "/api/x"), false);
  assert.equal(routeMatches(r, "GET", "/api/x/y"), false);
  assert.equal(routeMatches(r, "GET", "/api"), false);
  assert.equal(routeMatches(r, undefined, "/api/x"), false);
});

test("a prefix route matches every path under it, for its own method", () => {
  const r = route("DELETE", "/profile", () => {}, { prefix: true });
  assert.equal(routeMatches(r, "DELETE", "/profile"), true);
  assert.equal(routeMatches(r, "DELETE", "/profile/demo"), true);
  assert.equal(routeMatches(r, "DELETE", "/profiles"), true);
  assert.equal(routeMatches(r, "GET", "/profile/demo"), false);
  assert.equal(routeMatches(r, "DELETE", "/prof"), false);
});

test("dispatch runs the first matching route and reports the request handled", async () => {
  const calls = [];
  const routes = [route("GET", "/a", () => { calls.push("first"); }), route("GET", "/a", () => { calls.push("second"); })];
  assert.equal(await dispatch(routes, ctx("GET", "/a")), true);
  assert.deepEqual(calls, ["first"]);
});

test("dispatch reports false and runs nothing when no route matches", async () => {
  const calls = [];
  const routes = [route("GET", "/a", () => { calls.push("a"); })];
  assert.equal(await dispatch(routes, ctx("GET", "/b")), false);
  assert.equal(await dispatch(routes, ctx("POST", "/a")), false);
  assert.deepEqual(calls, []);
});

test("an async handler is awaited before dispatch resolves", async () => {
  let done = false;
  const routes = [route("GET", "/a", async () => { await new Promise((r) => setTimeout(r, 20)); done = true; })];
  await dispatch(routes, ctx("GET", "/a"));
  assert.equal(done, true);
});

test("a route marked mayDecline lets later routes answer when it resolves false", async () => {
  const calls = [];
  const routes = [
    route("GET", "/files/", async () => { calls.push("declined"); return false; }, { prefix: true, mayDecline: true }),
    route("GET", "/files/x", () => { calls.push("answered"); }),
  ];
  assert.equal(await dispatch(routes, ctx("GET", "/files/x")), true);
  assert.deepEqual(calls, ["declined", "answered"]);
});

test("a declining route with nothing after it leaves the request unhandled", async () => {
  const routes = [route("GET", "/files/", () => false, { prefix: true, mayDecline: true })];
  assert.equal(await dispatch(routes, ctx("GET", "/files/x")), false);
});

test("a handler that does not decline ends the search even if it returns false", async () => {
  const calls = [];
  const routes = [route("GET", "/a", () => false), route("GET", "/a", () => { calls.push("later"); })];
  assert.equal(await dispatch(routes, ctx("GET", "/a")), true);
  assert.deepEqual(calls, []);
});

test("a rejecting handler rejects dispatch, so the server's unhandled-rejection logging still sees it", async () => {
  const routes = [route("GET", "/a", async () => { throw new Error("boom"); })];
  await assert.rejects(() => dispatch(routes, ctx("GET", "/a")), /boom/);
});

test("the real route table lists no route twice", () => {
  const seen = new Set();
  for (const r of [...apiRoutes, ...lateRoutes]) {
    const key = `${r.method} ${r.path}${r.prefix ? "*" : ""}`;
    assert.equal(seen.has(key), false, `duplicate route ${key}`);
    seen.add(key);
  }
});

test("every route in the real table has an absolute path and a known method", () => {
  for (const r of [...apiRoutes, ...lateRoutes]) {
    assert.ok(r.path.startsWith("/"), r.path);
    assert.ok(["GET", "POST", "DELETE"].includes(r.method), `${r.method} ${r.path}`);
    assert.equal(typeof r.handle, "function", r.path);
  }
});
