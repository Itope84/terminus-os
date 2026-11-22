import { test } from "node:test";
import assert from "node:assert/strict";
import { parseLocationReply } from "./location.js";
import { tileOf } from "./map-tiles.js";

test("parseLocationReply accepts a fix and an error, rejects junk", () => {
  const ok = parseLocationReply({ id: "a1b2c3d4e5f60718", lat: 51.49, lng: 0.069, accuracy: 12.6, at: 1_800_000_000_000 });
  assert.deepEqual(ok, { id: "a1b2c3d4e5f60718", reply: { lat: 51.49, lng: 0.069, accuracy: 13, at: 1_800_000_000_000 } });
  assert.deepEqual(parseLocationReply({ id: "a1b2c3d4e5f60718", error: "permission_needed" })?.reply, { error: "permission_needed" });
  assert.equal(parseLocationReply({ id: "../x", lat: 1, lng: 1 }), null);
  assert.equal(parseLocationReply({ id: "a1b2c3d4e5f60718", lat: 91, lng: 0 }), null);
  assert.equal(parseLocationReply({ id: "a1b2c3d4e5f60718", lat: "x", lng: 0 }), null);
});

test("tileOf bounds-checks tile paths", () => {
  assert.deepEqual(tileOf("/terminus/_tiles/15/16390/10896.png"), { z: 15, x: 16390, y: 10896 });
  assert.equal(tileOf("/terminus/_tiles/2/4/0.png"), null); // x out of range at z2
  assert.equal(tileOf("/terminus/_tiles/20/0/0.png"), null); // beyond max zoom
  assert.equal(tileOf("/terminus/_tiles/../../etc.png"), null);
});
