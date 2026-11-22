# Contributing

Before writing code, file an issue describing the change (bug, skill, backend,
docs). Small, focused PRs merge fastest; one concern per PR.

- Skills: one directory under `skills/` starting with `SKILL.md` (name +
  description frontmatter). Keep them generic — no personal accounts, places,
  or routines. Publish locally with `bash tools/sync.sh --dry-run`, then `bash tools/sync.sh`.
- Server (`server/`): `npm run build` must pass and `npm test` must stay green.
  No secrets, tokens, or machine paths in code — env vars and repo-relative
  paths only.
- Android (`android/`): builds with `./gradlew assembleDebug` (Java 17 + SDK).
- Never commit: credentials, tokens, key files, `notebook/` contents, browser
  profiles, `*.apk`, local build outputs. When in doubt, leave it out of the PR.
