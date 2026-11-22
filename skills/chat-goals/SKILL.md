---
name: chat-goals
description: Set a chat objective and continue work until it is done or blocked.
---

# Chat goals — arm an objective, server judges each turn

One goal per chat. Arm → every settled turn gets a verdict
(`keep-going` / `done` / `stuck`, capped at 20) recorded to the goal
+ server log. Advisory by default; `loop: true` spends turns continuing
automatically (budgeted, kill-switchable).

## Picking the objective (the actual skill)

1. **Read the chat.** What's unresolved? What is it stuck on? If there's
   no open loop, say so — don't arm decoratively.
2. **Frame one checkable line** (≤500 chars, server-truncated): the
   finish state, not the activity. "Restore nightly sync so exports land
   before 06:00" beats "help with sync".
3. **Loop or advisory?** Default advisory (`loop` absent/false: judge
   and record only, free). `loop: true` only when the user wants the
   chat pursued without them — each continuation is a paid turn
   (default budget 1, max 20). 3× `stuck` → `blocked`, loop halts.
4. **Re-arm freely.** Arming over an existing goal resets
   stuck/continuations but keeps verdict/event history.

## Arm / manage (`:9009`, bearer-gated)

```bash
TOKEN=$(cat ~/.config/terminus/browser/token); BASE_URL=http://127.0.0.1:9009
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"<id>","objective":"<one line>","loop":false}' "$BASE_URL/api/goals/arm"
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"<id>"}' "$BASE_URL/api/goals/pause"   # resume | remove use the same shape
curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/goals"  # read all (GET)
```

## Facts that matter

- The check runs on the chat's own backend, model and effort (one throwaway call, no tools, no history, nothing saved); verdicts never enter the transcript (loop nudges render as dividers). A reply that isn't a clean verdict, or a backend that can't answer (quota), counts as a failed check: two in a row stop a looping goal.
- Kill switch (stops continuations, judging continues):
  `touch <checkout>/.run/goals-off` (or `GOALS_LOOP_OFF=1`).
- Store: `<repo>/.run/goals.json` (device state, gitignored).
  Secrets never belong in objectives — verdicts are logged.
