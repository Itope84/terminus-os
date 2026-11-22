#!/usr/bin/env bash
# Everything Terminus needs on its Mac, in one table: what it is, how to tell it's there, how to install it.
#
#   tools/deps.sh            check: list what's present and what's missing (changes nothing)
#   tools/deps.sh install    install what's missing (brew/npm/cargo/venvs/models); sign-ins stay manual
#   tools/deps.sh install <name>...   just those
#
# `make deps` / `make install-deps` run the same. Add a row when a skill or tool starts needing something.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
export PATH="$HOME/.local/bin:$HOME/.opencode/bin:/usr/local/bin:/opt/homebrew/bin:$PATH"

# name @ group @ check (exit 0 = present) @ install ("-" = manual, see note) @ note: what needs it / manual step
read -r -d '' DEPS <<'EOF'
brew        @ core    @ command -v brew                                   @ -                                              @ https://brew.sh, then rerun
node        @ core    @ command -v node                                   @ brew install node                              @ server (node dist/server.js)
python3.12  @ core    @ command -v python3.12                             @ brew install python@3.12                       @ skills
jq          @ core    @ command -v jq                                     @ brew install jq                                @ scripts
ripgrep     @ core    @ command -v rg                                     @ brew install ripgrep                           @ search
rtk         @ core    @ command -v rtk                                    @ -                                              @ token-saving shell wrapper: release binary from https://github.com/rtk-ai/rtk into ~/.local/bin
server-npm  @ core    @ test -d server/node_modules                       @ (cd server && npm ci)                          @ server dependencies
opencode    @ agents  @ command -v opencode                               @ curl -fsSL https://opencode.ai/install | bash  @ opencode backend; then `opencode auth login`
codex       @ agents  @ command -v codex                                  @ brew install --cask codex                      @ codex backend; then `codex login`
claude      @ agents  @ command -v claude                                 @ brew install --cask claude-code                    @ claude backend; then sign in once interactively
gemini      @ agents  @ command -v gemini                                 @ npm install -g @google/gemini-cli              @ gemini CLI
agy         @ agents  @ command -v agy                                    @ -                                              @ Antigravity backend: install its CLI from antigravity.google into ~/.local/bin
yt-dlp      @ skills  @ command -v yt-dlp                                 @ brew install yt-dlp                            @ fetch (captions, video)
ffmpeg      @ skills  @ command -v ffmpeg && command -v ffprobe           @ brew install ffmpeg                            @ media (video, gif, thumbnails)
saywhat      @ skills  @ command -v saywhat                                 @ cargo install --git https://github.com/aesrael/saywhat --root "$HOME/.local" @ live transcription, phone dictation (/api/listen); needs rust, cmake
saywhat-model @ skills  @ ls "$HOME/.local/share/saywhat/models/"*.gguf      @ saywhat download parakeet-unified-en-0.6b       @ saywhat's default model (731 MB)
fetch-py     @ skills  @ tools/fetch/.venv/bin/python -c 'import curl_cffi, trafilatura' @ python3 -m venv tools/fetch/.venv && tools/fetch/.venv/bin/pip install -q -r tools/fetch/requirements.txt @ fetch skill (its own venv)
rust        @ build   @ command -v cargo                                  @ brew install rust                              @ saywhat
cmake       @ build   @ command -v cmake                                  @ brew install cmake                             @ saywhat (speech engine)
openjdk17   @ build   @ test -x /usr/local/opt/openjdk@17/bin/java || test -x /opt/homebrew/opt/openjdk@17/bin/java @ brew install openjdk@17 @ Android builds
android-sdk @ build   @ command -v sdkmanager                             @ brew install --cask android-commandlinetools   @ Android builds; then accept licences: sdkmanager --licenses
EOF

mode=${1:-check}
shift 2>/dev/null || true
want=" $* "

missing=0
printf "%-13s %-7s %s\n" NAME GROUP STATUS
trim() { local v="$1"; v="${v#"${v%%[![:space:]]*}"}"; printf '%s' "${v%"${v##*[![:space:]]}"}"; }
while IFS= read -r line; do
  IFS=$'\x1f' read -r name group check install note <<< "${line// @ /$'\x1f'}"
  name=$(trim "$name"); group=$(trim "$group"); check=$(trim "$check"); install=$(trim "$install"); note=$(trim "$note")
  [ -z "$name" ] && continue
  if [ "$want" != "  " ] && [[ "$want" != *" $name "* ]]; then continue; fi
  if bash -c "$check" >/dev/null 2>&1; then
    printf "%-13s %-7s ok\n" "$name" "$group"
    continue
  fi
  missing=$((missing + 1))
  if [ "$mode" != "install" ]; then
    printf "%-13s %-7s MISSING  %s\n" "$name" "$group" "$note"
    continue
  fi
  if [ "$install" = "-" ]; then
    printf "%-13s %-7s MANUAL   %s\n" "$name" "$group" "$note"
    continue
  fi
  printf "%-13s %-7s installing: %s\n" "$name" "$group" "$install"
  if bash -c "$install" && bash -c "$check" >/dev/null 2>&1; then
    printf "%-13s %-7s ok  (%s)\n" "$name" "$group" "$note"
    missing=$((missing - 1))
  else
    printf "%-13s %-7s FAILED   %s\n" "$name" "$group" "$note"
  fi
done <<< "$DEPS"

echo
if [ "$missing" -eq 0 ]; then echo "all present"; else echo "$missing missing${mode:+ ($mode)}"; fi
[ "$mode" = "check" ] && [ "$missing" -gt 0 ] && echo "run: make install-deps"
exit 0
