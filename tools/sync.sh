#!/usr/bin/env bash
# terminus sync - publish skills & commands to agent discovery directories:
#   opencode    : ~/.config/opencode/skills/<name>/
#   claude      : ~/.claude/skills/<name>/
#   codex       : ~/.codex/skills/<name>/
#   antigravity : ~/.gemini/antigravity/knowledge/<name>/
#   pi        : ~/.pi/agent/skills/<name>/ + ~/.agents/skills/<name>/
#   claude cmds : ~/.claude/commands/<name>.md
#
# Terminus is source of truth — exact mirror per destination:
#   create : skill exists in terminus, missing locally -> copied in
#   update : skill exists in both, differs -> REPLACED (terminus wins,
#            local edits to a managed skill are overwritten, loudly)
#   delete : skill-shaped directory (contains SKILL.md) present locally
#            but absent from terminus/skills AND terminus/skills/third-party
#            -> PURGED, no exceptions. External skills belong vendored under
#            skills/third-party/, not hand-dropped in agent dirs.
# Non-skill entries (plain files, agent-owned data dirs without SKILL.md)
# are left alone and reported.
#
# Usage: bash tools/sync.sh [--dry-run]

set -euo pipefail

TERMINUS="${TERMINUS:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

OPENCODE_SKILLS="$HOME/.config/opencode/skills"
CLAUDE_SKILLS="$HOME/.claude/skills"
CODEX_SKILLS="$HOME/.codex/skills"
ANTIGRAVITY_SKILLS="$HOME/.gemini/antigravity/knowledge"
PI_SKILLS="$HOME/.pi/agent/skills"
AGENTS_SKILLS="$HOME/.agents/skills"
CLAUDE_COMMANDS="$HOME/.claude/commands"

MANIFEST=".terminus-manifest"

run() {
  if [ "$DRY_RUN" = 1 ]; then
    echo "  [dry-run] $*"
  else
    "$@"
  fi
}

# Collect source skill names (top-level + third-party flattened, as published).
declare -a SOURCE_NAMES=()
collect_sources() {
  SOURCE_NAMES=()
  if [ -d "$TERMINUS/skills" ]; then
    for skill in "$TERMINUS"/skills/*/; do
      [ -d "$skill" ] || continue
      [ -f "$skill/SKILL.md" ] || { echo "  skip $(basename "$skill") (no SKILL.md)"; continue; }
      SOURCE_NAMES+=("$(basename "$skill")")
    done
  fi
  if [ -d "$TERMINUS/skills/third-party" ]; then
    for skill in "$TERMINUS"/skills/third-party/*/; do
      [ -d "$skill" ] || continue
      [ -f "$skill/SKILL.md" ] || { echo "  skip third-party/$(basename "$skill") (no SKILL.md)"; continue; }
      SOURCE_NAMES+=("$(basename "$skill")")
    done
  fi
}

in_sources() {
  local want="$1" n
  for n in "${SOURCE_NAMES[@]}"; do [ "$n" = "$want" ] && return 0; done
  return 1
}

# Sync one destination root to the exact source set.
sync_dest() {
  local dest="$1" label="$2"
  run mkdir -p "$dest"
  echo "== $label ($dest)"
  # Create/update: every source skill lands, terminus winning conflicts.
  local name src
  for name in "${SOURCE_NAMES[@]}"; do
    if [ -d "$TERMINUS/skills/$name" ]; then src="$TERMINUS/skills/$name"
    else src="$TERMINUS/skills/third-party/$name"; fi
    if [ -d "$dest/$name" ]; then
      echo "  ~ $name (replace)"
    else
      echo "  + $name (create)"
    fi
    run rsync -a --delete --exclude='__pycache__' "$src/" "$dest/$name/"
  done
  # Purge: any skill-shaped dir (has SKILL.md) not in the source set goes,
  # no matter who put it there. Non-skill entries are reported, kept.
  local removed=0 kept=0
  for entry in "$dest"/*/; do
    [ -d "$entry" ] || continue
    name="$(basename "$entry")"
    if in_sources "$name"; then continue; fi
    if [ -f "$entry/SKILL.md" ]; then
      echo "  - $name (purged, not in terminus)"
      run rm -rf "$dest/$name"
      removed=$((removed + 1))
    else
      echo "  = $name (kept: not a skill, no SKILL.md)"
      kept=$((kept + 1))
    fi
  done
  # Obsolete manifests from the earlier scheme: ours, remove.
  if [ -f "$dest/$MANIFEST" ]; then run rm -f "$dest/$MANIFEST"; fi
  echo "  ($label: ${#SOURCE_NAMES[@]} managed, $removed purged, $kept non-skill kept)"
}

collect_sources

sync_dest "$OPENCODE_SKILLS" "opencode"
sync_dest "$CLAUDE_SKILLS" "claude"
sync_dest "$CODEX_SKILLS" "codex"
sync_dest "$ANTIGRAVITY_SKILLS" "antigravity"
sync_dest "$PI_SKILLS" "pi"
sync_dest "$AGENTS_SKILLS" "agents"

# Claude commands: same source-truth discipline. Managed = basenames under
# terminus/commands; any other *.md is foreign to this job and is purged
# the same way (user commands belong in versioned repos, not loose here).
if [ -d "$TERMINUS/commands" ]; then
  echo "== claude commands ($CLAUDE_COMMANDS)"
  run mkdir -p "$CLAUDE_COMMANDS"
  declare -a CMD_NAMES=()
  for cmd in "$TERMINUS"/commands/*.md; do
    [ -f "$cmd" ] || continue
    CMD_NAMES+=("$(basename "$cmd")")
    echo "  + $(basename "$cmd")"
    run cp "$cmd" "$CLAUDE_COMMANDS/$(basename "$cmd")"
  done
  if [ "${#CMD_NAMES[@]}" -gt 0 ]; then
    for existing in "$CLAUDE_COMMANDS"/*.md; do
      [ -f "$existing" ] || continue
      base="$(basename "$existing")"
      [ "$base" = "$MANIFEST" ] && continue
      found=0
      for c in "${CMD_NAMES[@]}"; do [ "$c" = "$base" ] && found=1; done
      if [ "$found" = 0 ]; then
        echo "  - $base (purged, not in terminus)"
        run rm -f "$CLAUDE_COMMANDS/$base"
      fi
    done
  fi
  if [ -f "$CLAUDE_COMMANDS/$MANIFEST" ]; then run rm -f "$CLAUDE_COMMANDS/$MANIFEST"; fi
fi

echo "Done."
