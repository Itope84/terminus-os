/** What the app can show in a reply and what the owner's phone and Mac give an agent: one line each, built
 *  into every backend's base prompt (system-prompt.ts). Short on purpose: the rules for each live in its skill.
 *  app-features.test.ts checks the app's renderers (android ChatMarkdown.kt) against `shows`, so a new display
 *  feature can't ship without a line here. `skill`: listed only when that skill ships in the checkout. */
import { statSync } from "node:fs";
import { join } from "node:path";
import { networkInterfaces } from "node:os";
import { execFileSync } from "node:child_process";
import { repoRoot } from "./paths.js";

export type AppFeature = { id: string; line: string; skill?: string };

/** Rendered by the app when they appear in a reply. `{gallery}` is the phone-reachable gallery base. */
export const SHOWS: AppFeature[] = [
  { id: "image", line: "Image: ![label](url) or the URL on its own line (png, jpg, gif, webp). Your own files: {gallery}/<day>/<file> (a Mac path doesn't load on the phone)." },
  { id: "video", line: "Video: an .mp4, .webm or .mov URL on its own line plays inline (same gallery URL for your files)." },
  { id: "map", skill: "location", line: "Map card: ```map fence with {\"places\":[{\"name\",\"lat\",\"lng\"}],\"me\":true,\"line\":true,\"path\":[[lat,lng],…],\"pathLabel\",\"zoom\"}; me = the owner's dot, line = distances to each place, path = a route." },
  { id: "alarm", line: "Alarm card: [text](terminus://alarm?at=<epochMs>&msg=<url-encoded>) (rules above)." },
  { id: "browser", skill: "browser-mobile", line: "Browser card: a terminus://browser link from the phone-browser skill opens that page on the phone." },
  { id: "links", line: "Links, emails and phone numbers are tappable when written plainly or as markdown links." },
];

/** Things an agent can use, by skill. */
export const TOOLS: AppFeature[] = [
  { id: "location", skill: "location", line: "The user's location: python3 skills/location/where.py (now, distance to a place, \"tell me when I'm near\" watchers)." },
  { id: "phone-browser", skill: "browser-mobile", line: "Phone browser for JS, logins and sites that block bots: skills/browser-mobile." },
  { id: "schedule", skill: "schedule", line: "Reminders, timed checks and recurring reports: skills/schedule." },
  { id: "notebook", skill: "notebook", line: "Notebook (facts, decisions, dates): skills/notebook." },
  { id: "inbox", skill: "inbox", line: "Send a result to the app inbox: skills/inbox." },
];

function hasSkill(name: string): boolean {
  try { return statSync(join(repoRoot(), "skills", name)).isDirectory(); } catch { return false; }
}

let host: string | null = null;
/** The Mac's address as the phone reaches it: Tailscale first, else the LAN. */
export function galleryBase(): string {
  if (host === null) {
    host = "";
    try {
      const out = execFileSync("tailscale", ["ip", "-4"], { encoding: "utf8", timeout: 3000, stdio: ["ignore", "pipe", "ignore"] }).trim().split("\n")[0]?.trim() ?? "";
      if (/^100\./.test(out)) host = out;
    } catch { /* not installed */ }
    if (!host) {
      const all = Object.values(networkInterfaces()).flat().filter((i): i is NonNullable<typeof i> => !!i);
      host = all.find((i) => i.family === "IPv4" && i.address.startsWith("100."))?.address
        ?? all.find((i) => i.family === "IPv4" && !i.internal)?.address ?? "127.0.0.1";
    }
  }
  const port = Number(process.env.PORT || process.env.HEADLESS_PORT || 9009);
  return `http://${host}:${port}/terminus`;
}

/** The prompt block: pure given the skill check and gallery base (tests pass their own). */
export function appFeaturesBlock(has: (skill: string) => boolean = hasSkill, gallery: string = galleryBase()): string {
  const pick = (fs: AppFeature[]) => fs.filter((f) => !f.skill || has(f.skill)).map((f) => `- ${f.line.replace("{gallery}", gallery)}`);
  return ["The app shows these in a reply:", ...pick(SHOWS), "", "You can also use:", ...pick(TOOLS)].join("\n");
}
