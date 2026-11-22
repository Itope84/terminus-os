---
name: artifacts
description: Place generated files in the artifacts gallery and provide a download link.
---

# Artifacts

Transient handoff shelf. Nothing lives here permanently.

- Drop into `artifacts/<today>/`, verify byte count (`stat`/`wc -c` vs server `size`), remove any Desktop copy. No `cmp` — local copies + size match is enough.
- Gallery `http://<server-host>:9009/terminus/`, `?download=1` forces download. Bearer-authed: verify with `Authorization: Bearer $(cat ~/.config/terminus/browser/token)`, expect 200 + matching `size_download` (human's browser carries auth, so links just work for them).
- Flat layout: `GET /terminus/<day>/<file>` only — no subfolders, rename distinctly (`report-lara.jpg`, not `img/lara.jpg`).
- 7-day prune (staged `terminus-chat*.apk` exempt). Wipe after confirmed download.
- Password shares at `artifacts/<name>/`, served read-only at `/share/<name>` (password set over the bearer API). Same prune rules — handoff, not archive.
- APKs: bump version, build, stage versioned copy to BOTH `$TMPDIR/terminus/<day>/` and here (byte count must match + curl `size_download`).
