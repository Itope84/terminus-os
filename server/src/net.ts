import { networkInterfaces } from "node:os";
import type { NetworkInterfaceInfoIPv4 } from "node:os";

const ipv4Interfaces = (): NetworkInterfaceInfoIPv4[] =>
  Object.values(networkInterfaces()).flat().filter((i): i is NetworkInterfaceInfoIPv4 => !!i && i.family === "IPv4");

/** This machine's Tailscale address (the 100.x range), or null when Tailscale is not running. Never a LAN address. */
export function tailnetIp(): string | null {
  return ipv4Interfaces().find((i) => i.address.startsWith("100."))?.address ?? null;
}

/** The address advertised to clients (media URLs): Tailscale first, then the LAN, then loopback. */
export function localIp(): string {
  return tailnetIp() ?? ipv4Interfaces().find((i) => !i.internal)?.address ?? "127.0.0.1";
}

/** The port this server listens on. */
export const port = Number(process.env.PORT || process.env.HEADLESS_PORT || 9009);

/** Base URL clients use to reach this server's media (gallery files, screenshots). */
export const mediaBase = () => `http://${localIp()}:${port}`;
