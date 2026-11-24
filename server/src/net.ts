import { networkInterfaces } from "node:os";

/** This machine's Tailscale IPv4 address, or null when Tailscale is not running. Never a LAN address. */
export function tailnetIp(): string | null {
  try {
    const { execSync } = require("node:child_process") as typeof import("node:child_process");
    const out = execSync("tailscale ip -4 2>/dev/null", { encoding: "utf8" }).trim().split("\n")[0]?.trim();
    if (out && /^100\./.test(out)) return out;
  } catch {}
  const all = Object.values(networkInterfaces()).flat().filter(Boolean) as import("node:os").NetworkInterfaceInfoIPv4[];
  return all.find((i) => i.family === "IPv4" && i.address.startsWith("100."))?.address ?? null;
}
/** The address advertised to clients (media URLs): Tailscale first, then the LAN, then loopback. */
export function localIp(): string {
  const tail = tailnetIp();
  if (tail) return tail;
  const all = Object.values(networkInterfaces()).flat().filter(Boolean) as import("node:os").NetworkInterfaceInfoIPv4[];
  const lan = all.find((i) => i.family === "IPv4" && !i.internal);
  return lan?.address || "127.0.0.1";
}

/** The port this server listens on. */
export const port = Number(process.env.PORT || process.env.HEADLESS_PORT || 9009);

/** Base URL clients use to reach this server's media (gallery files, screenshots). */
export const mediaBase = () => `http://${localIp()}:${port}`;
