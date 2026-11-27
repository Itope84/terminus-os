// After `expo export`: stamp the service worker with a build id and the files to keep for offline use.
// Every build changes sw.js, so browsers install the new worker, which caches the new files and drops the old
// ones. Usage: node scripts/build-sw.mjs <dist dir>
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const dist = process.argv[2];
if (!dist) {
  console.error('usage: node scripts/build-sw.mjs <dist dir>');
  process.exit(1);
}

/** Not part of the app the browser loads. */
const SKIP = new Set(['sw.js', '_headers', 'metadata.json', 'index.html', 'manifest.webmanifest']);

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

const files = walk(dist)
  .map((f) => relative(dist, f).split(sep).join('/'))
  .filter((f) => !SKIP.has(f) && !f.endsWith('.map'))
  // Expo exports every icon font; the app only draws Ionicons. Others are cached if ever used.
  .filter((f) => !f.includes('/vector-icons/') || f.includes('/Ionicons.'))
  .sort();

const hash = createHash('sha256');
for (const f of [...files, 'index.html']) hash.update(f).update(readFileSync(join(dist, f)));
const build = hash.digest('hex').slice(0, 12);

// The page itself is cached as `/app/` (the server serves index.html there).
const shell = ['/app/', ...files.map((f) => `/app/${encodeURI(f)}`)];
const swPath = join(dist, 'sw.js');
const sw = readFileSync(swPath, 'utf8')
  .replace("'__TERMINUS_BUILD__'", JSON.stringify(build))
  .replace('[] /* __TERMINUS_SHELL__ */', JSON.stringify(shell));
if (!sw.includes(`const BUILD = "${build}"`)) throw new Error('sw.js placeholders not found');
writeFileSync(swPath, sw);
const mb = files.reduce((n, f) => n + statSync(join(dist, f)).size, 0) / 1048576;
console.log(`sw.js: build ${build}, ${shell.length} files, ${mb.toFixed(1)} MB`);
