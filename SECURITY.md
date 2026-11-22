# Security

Terminus is a local-first personal system: the server listens on this
machine only by default, auth is a bearer token in a `0600` file, and credentials live
in environment variables or key files — never in the repo.

- Do not open issues containing tokens, keys, personal data, or device details.
  Redact logs before posting (`safeMessage`-style: drop bearer values, paths, ids).
- Suspected vulnerability (auth bypass, token leak, sandbox escape in a backend
  adapter, gallery/share exposure)? Report it privately through GitHub: open the
  repository's **Security** tab and choose **Report a vulnerability**. Please do
  not file a public issue for anything exploitable.
- Network exposure: by default the server accepts connections from this machine only
  (plus a VPN address such as Tailscale's when one is running; see "Using it on the
  go" in the README). Setting `HOST=0.0.0.0` makes it reachable from every network
  you are on; do that only behind a firewall or VPN.
- Cleartext traffic: the Android app allows plain HTTP because the server is
  normally reached over a private, already-encrypted network. On any other
  network, put the server behind HTTPS (for example a reverse proxy).
- Dependencies: check `npm audit` (server) and Gradle reports (android) before
  release builds; do not commit lockfile downgrades without noting why.
