#!/usr/bin/env python3
"""terminus schedule — local cron for reminders, checks, and watchers.

Jobs live in ~/.config/terminus/schedule/jobs.json (personal data, not git).
A per-minute LaunchAgent ticker calls `run-due`; alerts fire into the
Terminus inbox (+ fired.log). See SKILL.md.
"""

import argparse
import fcntl
import json
import os
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

STORE_DIR = os.path.expanduser("~/.config/terminus/schedule")
JOBS_FILE = os.path.join(STORE_DIR, "jobs.json")
CONFIG_FILE = os.path.join(STORE_DIR, "config.json")
FIRED_LOG = os.path.join(STORE_DIR, "fired.log")
STATE_DIR = os.path.join(STORE_DIR, "state")
STATE_MAX = 8 * 1024  # bytes; the file rides in every run's prompt, so it stays small
LOCK_FILE = os.path.join(STORE_DIR, "run-due.lock")
OPENCODE_BIN = os.path.expanduser("~/.opencode/bin/opencode")
CODEX_BIN = "/usr/local/bin/codex"
CLAUDE_BIN = "/usr/local/bin/claude"
CLAUDE_MODEL = "claude-haiku-5-5"  # all scheduled agent runs use Haiku
# Mirror of server/src/backends/claude.ts CLAUDE_ALLOWED_TOOLS /
# CLAUDE_DISALLOWED_TOOLS (opencode parity). That file is the source of truth.
CLAUDE_ALLOWED_TOOLS = "Bash Edit Write Read Glob Grep WebFetch WebSearch TodoWrite"
_CLAUDE_DENY_PATHS = [
    "~/.config/terminus/secrets/**",
    "~/.config/opencode/auth.json",
    "**/.env*",
    "**/notebook/20*-W*.md",
    "**/notebook/user.md",
]
CLAUDE_DISALLOWED_TOOLS = " ".join(
    f"{t}({p})" for p in _CLAUDE_DENY_PATHS for t in ("Read", "Edit", "Write"))
HARNESSES = ("opencode", "codex", "claude")

CHAT_RE = None  # compiled lazily (see valid_chat)


def valid_chat(v):
    """Session/thread id override for targeted delivery (server CHATID_RE)."""
    import re as _re
    global CHAT_RE
    if CHAT_RE is None:
        CHAT_RE = _re.compile(r"^[A-Za-z0-9_:-]{1,80}$")
    return isinstance(v, str) and bool(CHAT_RE.match(v))


def server_default_backend():
    """The harness the app is currently switched to (auto target)."""
    candidates = [
        os.path.join(ROOT_DIR, ".run", "chat-defaults.json"),
    ]
    for path in candidates:
        try:
            raw = open(path).read()
            b = json.loads(raw).get("defaultBackend")
            if b in HARNESSES:
                return b
        except Exception:
            continue
    return "opencode"


def harness_order(job):
    """Resolve which harness(es) to try. Missing key = legacy opencode-only.
    auto = current default first, then the rest; first success wins."""
    h = job.get("harness") or "claude"
    if h in HARNESSES:
        return [h]
    if h == "auto":
        first = server_default_backend()
        return [first] + [x for x in HARNESSES if x != first]
    return ["claude"]


def run_agent_once(harness, payload, title):
    """One headless agent run. Returns (output, failed, note)."""
    if harness == "codex":
        import tempfile
        with tempfile.NamedTemporaryFile("r", prefix="codex-last-", suffix=".txt", delete=False) as f:
            tmp = f.name
        try:
            out, failed, note = _run_watched(
                [CODEX_BIN, "exec", "--json", "--skip-git-repo-check",
                 "-s", "workspace-write",
                 # Scheduled briefs call live APIs (weather, Google, feeds).
                 # Codex disables shell networking in workspace-write by default;
                 # opt in here, retaining the workspace filesystem boundary.
                 "-c", "sandbox_workspace_write.network_access=true",
                 "-o", tmp, payload],
                False, WORKDIR, 1500, 240, title,
                # Go CLIs need a PEM bundle in Codex's macOS
                # sandbox; Keychain-based system-root lookup fails there.
                extra_env={"SSL_CERT_FILE": "/etc/ssl/cert.pem"})
        except FileNotFoundError:
            return f"codex binary not found at {CODEX_BIN}", True, ""
        try:
            text = open(tmp).read().strip()
        except Exception:
            text = ""
        finally:
            try:
                os.unlink(tmp)
            except Exception:
                pass
        if failed or not text:
            return text or "codex produced no output", True, note
        return text, False, note
    if harness == "claude":
        try:
            out, failed, note = _run_watched(
                [CLAUDE_BIN, "-p", payload, "--model", CLAUDE_MODEL, "--output-format", "json",
                 "--permission-mode", "dontAsk",
                 "--allowedTools", CLAUDE_ALLOWED_TOOLS,
                 "--disallowedTools", CLAUDE_DISALLOWED_TOOLS],
                False, WORKDIR, 1500, 240, title)
        except FileNotFoundError:
            return f"claude binary not found at {CLAUDE_BIN}", True, ""
        if failed or not out.strip():
            return out or "claude produced no output", True, note
        try:
            env = json.loads(out.strip().splitlines()[-1])
        except Exception:
            return "claude returned unparseable output", True, note
        if not isinstance(env, dict) or env.get("is_error"):
            return str(env)[:500], True, note
        text = str(env.get("result", "") or "").strip()
        if not text:
            return "claude returned empty result", True, note
        return text, False, note
    # opencode (legacy path, unchanged)
    try:
        out, failed, note = _run_watched(
            [OPENCODE_BIN, "run", "--auto", payload], False, WORKDIR, 1500, 240, title)
        if failed:
            out = "agent job silent for 4 min twice — killed, no output" + note
        elif not out.strip():
            out, failed = "(no output)", True
        return out, failed, note
    except FileNotFoundError:
        return f"opencode binary not found at {OPENCODE_BIN}", True, ""
WORKDIR = os.path.expanduser("~")


def load_config():
    """Phone-push config. Created by `schedule.py setup`."""
    try:
        with open(CONFIG_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def load_jobs():
    try:
        with open(JOBS_FILE) as f:
            return json.load(f).get("jobs", [])
    except (FileNotFoundError, json.JSONDecodeError):
        return []


def save_jobs(jobs):
    os.makedirs(STORE_DIR, exist_ok=True)
    tmp = JOBS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"jobs": jobs}, f, indent=2)
    os.replace(tmp, JOBS_FILE)


def parse_every(spec):
    m = re.fullmatch(r"(\d+)(m|h|d)", spec.strip())
    if not m:
        raise ValueError(f"bad --every spec: {spec!r} (use like 10m, 1h, 1d)")
    n, unit = int(m.group(1)), m.group(2)
    return {"m": 1, "h": 60, "d": 1440}[unit] * n


def next_from_cron(cron, now):
    """Next datetime matching a 5-field cron (M H DOM MON DOW), minute precision."""
    fields = cron.split()
    if len(fields) != 5:
        raise ValueError(f"bad --cron spec: {cron!r} (need 5 fields)")
    mins, hours, doms, mons, dows = (parse_field(f, lo, hi) for f, lo, hi in
                                     zip(fields, (0, 0, 1, 1, 0), (59, 23, 31, 12, 6)))
    t = now.replace(second=0, microsecond=0) + timedelta(minutes=1)
    for _ in range(525600 * 2):  # give up after ~2 years
        dow = (t.weekday() + 1) % 7  # cron: 0=Sunday
        if (t.minute in mins and t.hour in hours and t.day in doms
                and t.month in mons and dow in dows):
            return t
        t += timedelta(minutes=1)
    raise ValueError(f"cron never matches: {cron!r}")


def parse_field(field, lo, hi):
    out = set()
    for part in field.split(","):
        step = 1
        if "/" in part:
            part, step = part.split("/", 1)
            step = int(step)
        if part in ("*", ""):
            out.update(range(lo, hi + 1, step))
        elif "-" in part:
            a, b = part.split("-", 1)
            out.update(range(int(a), int(b) + 1, step))
        else:
            out.add(int(part))
    return {v for v in out if lo <= v <= hi}


def compute_next(job, now):
    if job.get("at"):
        at = datetime.strptime(job["at"], "%Y-%m-%d %H:%M")
        return at if at > now else None  # one-shot in the past: done
    if job.get("cron"):
        return next_from_cron(job["cron"], now)
    if job.get("every_min"):
        last = job.get("last_run")
        base = datetime.fromtimestamp(last) if last else now
        nxt = base + timedelta(minutes=job["every_min"])
        return nxt if nxt > now else now + timedelta(minutes=1)
    return None


def notify(title, body, priority="default", tags=None, chat=None):
    """Fire into the Terminus inbox (the only delivery leg).

    POST /api/inbox lands as a message in the job's stable
    chat (`schedule:<slug>`) — bell + unread, tap to open.
    chat= (optional session id) routes into that exact thread instead.
    """
    full = (body or "").strip()
    short = full[:1500]
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
    os.makedirs(STORE_DIR, exist_ok=True)
    with open(FIRED_LOG, "a") as f:
        f.write(f"[{ts}] {title}\n{full[:4000]}\n---\n")
    post_inbox(title, full)
    print(f"[{ts}] {title}: {short}")


def md_headers_for_terminus(body):
    """Phone briefs use *ASTERISK* headers; terminus is markdown-native,
    so upgrade standalone asterisk-header lines to ## headers."""
    import re as _re
    out = []
    for line in (body or "").splitlines():
        m = _re.match(r"^(\S+\s+)?\*([^*]+)\*\s*$", line.strip())
        if m and len(m.group(2).strip()) > 1:
            out.append("## " + (m.group(1) or "") + m.group(2).strip())
        else:
            out.append(line)
    return "\n".join(out)


def post_inbox(title, body, chat=None):
    """Mirror every delivery into the terminus inbox (best-effort).
    Server keeps history per job; the app pulls it into job chats."""
    try:
        import urllib.request
        token = Path.home().joinpath(".config/terminus/browser/token").read_text().strip()
        payload = json.dumps({
            "job": slugify(title),
            "title": title,
            "body": md_headers_for_terminus(body)[:15000],
            **({"chat": chat} if chat else {}),
        }).encode()
        req = urllib.request.Request(
            "http://127.0.0.1:9009/api/inbox", data=payload,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
        urllib.request.urlopen(req, timeout=15).read()
    except Exception as e:
        print(f"inbox mirror skipped ({type(e).__name__})")


def slugify(title):
    slug = re.sub(r"[^a-z0-9]+", "-", (title or "misc").lower()).strip("-")
    return slug[:60] or "misc"


def cmd_setup(_args):
    print("Jobs fire into the Terminus inbox (+ fired.log). No other delivery leg exists.")
    return 0


def apply_adaptive_jump(job, clean):
    """Strip NEXT_RUN line and jump next_run forward. Returns cleaned output."""
    m = re.search(r"^NEXT_RUN=(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s*$",
                  clean, re.MULTILINE)
    if not m:
        return clean
    try:
        nxt = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M")
    except ValueError:
        return clean
    clean = re.sub(r"^NEXT_RUN=\d{4}-\d{2}-\d{2} \d{2}:\d{2}\s*$", "",
                   clean, flags=re.MULTILINE).strip()
    if nxt > datetime.now():
        job["next_run"] = nxt.timestamp()
        job["adaptive_jump"] = m.group(1)
        job["_jumped"] = True
    return clean


def _list_session_ids():
    """Best-effort snapshot of top-level session ids in WORKDIR.

    Returns dict id -> created_ms. Empty on any failure; cleanup must
    never break delivery.
    """
    try:
        r = subprocess.run([OPENCODE_BIN, "session", "list", "--format", "json"],
                           capture_output=True, text=True,
                           timeout=30, cwd=WORKDIR)
        items = json.loads(r.stdout or "[]")
        return {s.get("id"): (s.get("created") or 0) for s in items if s.get("id")}
    except Exception:
        return {}


def _cleanup_run_sessions(before_ids, run_start_ms, job_name):
    """Delete top-level sessions created by this run (children cascade).

    Only touches ids absent before the run and created at/after run start,
    so concurrent user chats started earlier are never deleted. Best-effort:
    logs to stdout, never raises. Set TERMINUS_KEEP_RUN_SESSIONS=1 to keep
    sessions for post-mortem (timeouts).
    """
    import os as _os
    if _os.environ.get("TERMINUS_KEEP_RUN_SESSIONS") == "1":
        print(f"cleanup {job_name}: kept (TERMINUS_KEEP_RUN_SESSIONS=1)")
        return
    try:
        after = _list_session_ids()
        fresh = [sid for sid, created in after.items()
                 if sid not in before_ids and (created or 0) >= run_start_ms - 5000]
        for sid in fresh:
            try:
                subprocess.run([OPENCODE_BIN, "session", "delete", sid],
                               capture_output=True, text=True,
                               timeout=60, cwd=WORKDIR)
            except Exception as e:
                print(f"cleanup {job_name}: failed to delete {sid[:8]} ({e})",
                      file=sys.stderr)
        if fresh:
            print(f"cleanup {job_name}: deleted {len(fresh)} session(s)")
    except Exception as e:
        print(f"cleanup {job_name}: skipped ({e})", file=sys.stderr)


def _run_watched(argv, shell, cwd, total_s, first_output_s, label, extra_env=None):
    """Run a job with a first-output watchdog. Silent-start hangs (wedged
    spawn, deadlocked stdio) are killed after first_output_s and retried
    once; a still-silent retry fails fast instead of burning total_s.
    Returns (output_text, failed_bool, note_str)."""
    import select

    def once(extra_env=None):
        env = None
        if extra_env:
            env = dict(os.environ)
            env.update(extra_env)
        if shell:
            p = subprocess.Popen(argv, shell=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True,
                                 cwd=cwd, env=env)
        else:
            p = subprocess.Popen(argv, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True,
                                 cwd=cwd, env=env)
        chunks = []
        deadline = time.time() + total_s
        first_deadline = time.time() + first_output_s
        started_output = False
        hit_total = False
        try:
            while True:
                if p.poll() is not None:
                    break
                remaining = deadline - time.time()
                if remaining <= 0:
                    hit_total = True
                    chunks.append("\n[truncated: exceeded total budget]")
                    break
                r, _, _ = select.select([p.stdout], [], [], min(5.0, remaining))
                if r:
                    data = os.read(p.stdout.fileno(), 65536)
                    if not data:
                        continue
                    chunks.append(data.decode(errors="replace") if isinstance(data, bytes) else data)
                    started_output = True
                elif not started_output and time.time() >= first_deadline:
                    break  # silent past first-output budget
            try:
                p.wait(timeout=10)
            except Exception:
                pass
            if p.poll() is None:
                try:
                    p.kill()
                except Exception:
                    pass
                try:
                    p.wait(timeout=10)
                except Exception:
                    pass
            # Drain whatever is left without blocking.
            try:
                import fcntl as _fcntl
                fl = _fcntl.fcntl(p.stdout.fileno(), _fcntl.F_GETFL)
                _fcntl.fcntl(p.stdout.fileno(), _fcntl.F_SETFL, fl | os.O_NONBLOCK)
                while True:
                    try:
                        more = os.read(p.stdout.fileno(), 65536)
                    except Exception:
                        break
                    if not more:
                        break
                    chunks.append(more.decode(errors="replace") if isinstance(more, bytes) else more)
            except Exception:
                pass
            return "".join(chunks), started_output
        finally:
            try:
                p.stdout.close()
            except Exception:
                pass

    out1, started1 = once(extra_env)
    if started1 or len(out1.strip()) > 0:
        return out1, False, ""
    # Silent past first-output budget: one retry, then fail fast.
    out2, started2 = once(extra_env)
    if started2 or len(out2.strip()) > 0:
        return out2, False, " (recovered on retry after silent start)"
    return "", True, "silent start twice — killed, no output"


# ---- sealed delivery -------------------------------------------------------
# An agent's prose never reaches the phone for jobs that declare an artifact.
# The runner delivers the ARTIFACT's bytes (or a receipt it generates itself)
# after an optional gate command passes. Agent narration, lint loops and shell
# transcripts are logged to fired.log only. On any failure the phone gets one
# short line, never a transcript. Placeholders in paths/commands: {file}, {date}.
SCHED_DIR = os.path.dirname(os.path.abspath(__file__))
CHECKS_DIR = os.path.join(SCHED_DIR, "checks")
ROOT_DIR = os.path.abspath(os.path.join(SCHED_DIR, "..", ".."))


def sealed_defaults(job):
    """Opt-in sealed delivery: with --artifact set, the agent writes the file,
    the runner gates it and delivers its bytes; narration never ships."""
    if job.get("artifact"):
        return job
    return job


def artifact_path(job):
    return os.path.expanduser((job.get("artifact") or "").replace(
        "{date}", datetime.now().strftime("%Y-%m-%d")))


def _subst(text, path):
    import shlex
    return (text or "").replace("{file}", shlex.quote(path or "")).replace(
        "{date}", datetime.now().strftime("%Y-%m-%d"))


def _sealed_fail(job, title, why, agent_out, before_ids=None):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
    try:
        with open(FIRED_LOG, "a") as f:
            f.write(f"[{ts}] {title}: SEALED FAIL: {why}\nagent said: {(agent_out or '')[:800]}\n---\n")
    except Exception:
        pass
    notify(title, f"\u26a0 {title}: not delivered \u2014 {why}"[:300], "high", chat=job.get("chat"))
    return False


def deliver_sealed(job, title, prio, agent_out, run_start, before_ids):
    """Returns True if something was delivered (or deliberately silent)."""
    path = artifact_path(job)
    try:
        st = os.stat(path)
    except OSError:
        return _sealed_fail(job, title, "no artifact written", agent_out)
    if st.st_mtime * 1000 < run_start - 2000:
        return _sealed_fail(job, title, "artifact is stale (not written this run)", agent_out)
    if st.st_size == 0:
        return _sealed_fail(job, title, "artifact empty", agent_out)
    gate = job.get("gate")
    if gate:
        try:
            g = subprocess.run(_subst(gate, path), shell=True, capture_output=True,
                               text=True, timeout=60, cwd=WORKDIR)
        except subprocess.TimeoutExpired:
            return _sealed_fail(job, title, "gate timed out", agent_out)
        if g.returncode != 0:
            tail = [l for l in (g.stdout + g.stderr).splitlines() if l.strip()]
            return _sealed_fail(job, title, "gate failed: " + (tail[0][:140] if tail else f"exit {g.returncode}"), agent_out)
    rc = job.get("receipt_cmd")
    if rc:
        try:
            r = subprocess.run(_subst(rc, path), shell=True, capture_output=True,
                               text=True, timeout=30, cwd=WORKDIR)
            lines = [l.strip() for l in r.stdout.splitlines() if l.strip()]
        except Exception:
            lines = []
        body = lines[0][:300] if lines else f"{title}: updated"
    else:
        with open(path, encoding="utf-8", errors="replace") as f:
            body = f.read().strip()
    if body == "SILENT":
        ts = datetime.now().strftime("%Y-%m-%d %H:%M")
        with open(FIRED_LOG, "a") as f:
            f.write(f"[{ts}] {title}\nSILENT (suppressed)\n---\n")
        return False
    try:
        with open(FIRED_LOG, "a") as f:
            f.write(f"agent said (not delivered): {(agent_out or '')[:800]}\n")
    except Exception:
        pass
    notify(title, body, prio, chat=job.get("chat"))
    return True


def strip_transcript_lines(text):
    """Drop tool-transcript lines from any agent reply: harness echoes, shell
    commands, heredocs, lint/wc output, tracebacks. None of these ever belong
    in a phone message. Phone-formatted verdicts never match these shapes,
    so this runs on every agent delivery, marker or not."""
    _drop = re.compile(
        r"^(>\s|\$\s|EOF$|EXIT:\d*|LINT\s+(OK|FAIL)|FAIL\s|COMPILE-\w+"
        r"|\d+\s+/tmp/\S+|/tmp/\S+\.\w+|PY$|p=\"/tmp/)")
    kept, _skip_heredoc = [], False
    for l in (text or "").splitlines():
        s = l.strip()
        if "<<'PY'" in s or '<<"PY"' in s:
            _skip_heredoc = True
            continue
        if _skip_heredoc:
            if s == "PY":
                _skip_heredoc = False
            continue
        if s and _drop.match(s):
            continue
        kept.append(l)
    return "\n".join(kept).strip()


def state_path(job):
    return os.path.join(STATE_DIR, re.sub(r"[^A-Za-z0-9_.-]", "_", job["name"]) + ".json")


def state_begin(job):
    """--state jobs: make sure the file exists (0600), snapshot it, and return the prompt addendum."""
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    path = state_path(job)
    if not os.path.exists(path):
        with open(path, "w") as f:
            f.write("{}\n")
    os.chmod(path, 0o600)
    with open(path) as f:
        snap = f.read()
    with open(path + ".prev", "w") as f:
        f.write(snap)
    os.chmod(path + ".prev", 0o600)
    return (f"\n\nState file: {path} (JSON, at most {STATE_MAX // 1024} KB). Read it first: it holds what earlier runs "
            "already reported (ids, open items, last alerts). Don't repeat an alert unless something materially changed. "
            "Before you finish, rewrite it with what the next run needs, dropping the oldest entries to stay under the cap. "
            "Never store secrets, tokens or message bodies; ids, dates and one-line reasons only.")


def state_end(job):
    """After the run: an oversized or broken state file is rolled back to the snapshot. Returns a warning or ""."""
    path = state_path(job)
    try:
        size = os.path.getsize(path)
        with open(path) as f:
            json.load(f)
        if size <= STATE_MAX:
            return ""
        why = f"state file over {STATE_MAX // 1024} KB ({size} bytes)"
    except Exception as e:
        why = f"state file unreadable ({type(e).__name__})"
    try:
        os.replace(path + ".prev", path)
    except OSError:
        pass
    return why + ", rolled back"


def run_precheck(job, title):
    """--precheck: a script decides whether the model runs. Exit 0 + empty stdout = nothing new (skip the
    agent); exit 0 + output = run, with that output in the prompt; anything else = a failure alert, so a broken
    check can never silence a job. Returns (skip, extra, failed_why)."""
    env_cmd = f"export SCHEDULE_STATE={shlex.quote(state_path(job))}; {job['precheck']}"
    try:
        r = subprocess.run(["/bin/bash", "-lc", env_cmd], cwd=WORKDIR, capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired:
        return False, "", "pre-check timed out after 3 min"
    if r.returncode != 0:
        tail = (r.stderr or r.stdout or "").strip().splitlines()
        return False, "", f"pre-check exited {r.returncode}" + (f": {tail[-1][:120]}" if tail else "")
    out = (r.stdout or "").strip()
    return (not out), out[:6000], ""


def run_job(job):
    kind, payload = job["type"], job["payload"]
    title = job.get("title", job["name"])
    prio = job.get("priority", "default")
    if kind == "remind":
        notify(title, payload, prio, chat=job.get("chat"))
        return True  # alerted — caller may retire on alert
    out, failed = "", False
    state_warn = ""
    if kind == "agent" and job.get("precheck"):
        skip, extra, why = run_precheck(job, title)
        ts = datetime.now().strftime("%Y-%m-%d %H:%M")
        if why:
            with open(FIRED_LOG, "a") as f:
                f.write(f"[{ts}] {title}: {why}\n---\n")
            notify(title, f"\u26a0 {title}: {why}", "high", chat=job.get("chat"))
            return False
        if skip:
            with open(FIRED_LOG, "a") as f:
                f.write(f"[{ts}] {title}\npre-check: nothing new, agent not run\n---\n")
            print(f"[{ts}] {title}: pre-check quiet, agent skipped")
            return False
        job = dict(job, payload=job["payload"] + "\n\nNew since the last run (from the pre-check):\n" + extra)
        payload = job["payload"]
    if kind == "agent" and job.get("state"):
        job = dict(job, payload=job["payload"] + state_begin(job))
        payload = job["payload"]
    if kind == "agent":
        job = sealed_defaults(job)
        if job.get("artifact"):
            try:  # a stale artifact from an earlier run must never be delivered
                os.unlink(artifact_path(job))
            except OSError:
                pass
    if kind == "exec":
        out, failed, note = _run_watched(payload, True, WORKDIR, 600, 120, title)
        if failed:
            out = "job produced no output in 2 min (killed, retried once)" + note
        elif not out.strip():
            out = "(no output)"
    elif kind == "agent":
        before_ids = _list_session_ids()
        run_start_ms = int(datetime.now().timestamp() * 1000)
        out, failed, note, used = "", True, "", "none"
        for h in harness_order(job):
            out, failed, note = run_agent_once(h, payload, title)
            used = h
            if not failed:
                break
        try:
            with open(FIRED_LOG, "a") as f:
                f.write(f"harness: {used} (tried {','.join(harness_order(job))})\n")
        except Exception:
            pass
        if job.get("state"):
            state_warn = state_end(job)
            if state_warn:
                with open(FIRED_LOG, "a") as f:
                    f.write(f"{title}: {state_warn}\n")
        if failed:
            try:
                after = _list_session_ids()
                fresh = [sid for sid in after if sid not in before_ids]
                with open(FIRED_LOG, "a") as f:
                    f.write(f"kept sessions: {', '.join(s[:12] for s in fresh) or 'none found'}\n")
            except Exception:
                pass
        if job.get("artifact"):
            sent = deliver_sealed(job, title, prio, out, run_start_ms, before_ids)
            if sent:
                _cleanup_run_sessions(before_ids, run_start_ms, job.get("name", "?"))
            return sent
    else:
        print(f"unknown job type: {kind}", file=sys.stderr)
        return False
    if not failed:
        clean = re.sub(r"\x1b\[[0-9;]*m", "", out).strip()
        if kind == "agent" and not job.get("start_marker") and not job.get("artifact"):
            # Unmarked, unsealed agent jobs deliver their reply verbatim: strip
            # the tool transcript first so a flailing run degrades to its prose.
            clean = strip_transcript_lines(clean)
        if job.get("adaptive"):
            # Adaptive jobs end with NEXT_RUN=YYYY-MM-DD HH:MM naming the next
            # date worth firing on. Strip it before delivery; the ticker jumps
            # there, skipping quiet days entirely (no run = no inference cost).
            clean = apply_adaptive_jump(job, clean)
        lines = [l.strip() for l in clean.splitlines() if l.strip()]
        marker = job.get("start_marker")
        if marker:
            # Opt-in per job: drop agent progress chatter above the real
            # output (e.g. morning brief starts at its first section header).
            # Tolerant match: strip markdown/quote prefixes, then substring —
            # headers drift between "🌅 *GOOD..." and "## 🌅 GOOD...", and a
            # missed strip used to push the whole transcript to the phone.
            def _is_start(l):
                norm = l.strip().lstrip("#>*- ").strip()
                return norm.startswith(marker) or marker in norm
            ls = clean.splitlines()
            hits = [i for i, l in enumerate(ls) if _is_start(l)]
            if hits:
                # Last header wins: agents sometimes paste a draft, run a
                # lint/fix loop, then print the final. Everything between the
                # draft header and the final header is working transcript.
                clean = "\n".join(ls[hits[-1]:]).strip()
            # Drop tool-transcript lines that can appear mid-output (shared
            # helper also runs on unmarked jobs below).
            clean = strip_transcript_lines(clean)
            lines = [l.strip() for l in clean.splitlines() if l.strip()]
            if not hits:
                # Marker missing = no real output produced. Never push the
                # raw transcript to the phone; log a short failure instead.
                ts = datetime.now().strftime("%Y-%m-%d %H:%M")
                with open(FIRED_LOG, "a") as f:
                    f.write(f"[{ts}] {title}: no start-marker found, transcript suppressed ({len(clean)} chars)\n")
                print(f"[{ts}] {title}: no start-marker, suppressed")
                if kind == "agent":
                    _cleanup_run_sessions(before_ids, run_start_ms, job.get("name", "?"))
                return False
        if clean == "SILENT" or (lines and lines[-1] == "SILENT"):
            # job decided nothing is worth interrupting for — log, don't push
            # last-line match tolerates agent progress chatter above the verdict
            ts = datetime.now().strftime("%Y-%m-%d %H:%M")
            with open(FIRED_LOG, "a") as f:
                f.write(f"[{ts}] {title}\nSILENT (suppressed)\n{clean[:500]}\n---\n")
            print(f"[{ts}] {title}: SILENT (suppressed)")
            if kind == "agent":
                _cleanup_run_sessions(before_ids, run_start_ms, job.get("name", "?"))
            return False
    if failed:
        # never push a raw agent/TUI transcript: log it, send one short line
        try:
            with open(FIRED_LOG, "a") as f:
                f.write(f"FAILED {title}; raw output:\n{(out or '')[-4000:]}\n---\n")
        except Exception:
            pass
        last = [l.strip() for l in (out or "").splitlines() if l.strip()]
        why = (last[-1] if last else "no output")[:140]
        notify(title, f"\u26a0 {title}: run failed \u2014 {why}", "high", chat=job.get("chat"))
        return False
    if state_warn:
        clean += f"\n\n_\u26a0 {state_warn}_"
    notify(title, clean, prio, chat=job.get("chat"))
    if kind == "agent" and not failed:
        # reuse is broken on headless runs (serve can't continue them), and
        # history lives in fired.log + delivery — delete parent + children now.
        _cleanup_run_sessions(before_ids, run_start_ms, job.get("name", "?"))
    return not failed


def cmd_add(args):
    jobs = load_jobs()
    if any(j["name"] == args.name for j in jobs):
        print(f"job {args.name!r} already exists (remove it first)", file=sys.stderr)
        return 1
    now = datetime.now()
    job = {"name": args.name, "type": args.type, "payload": args.payload,
           "title": args.title or args.name, "created": now.isoformat(),
           "priority": args.priority,
           "fires": 0, "max_fires": args.count, "until": args.until,
           "retire_on_alert": bool(args.retire_on_alert)}
    if args.type == "agent":
        job["harness"] = args.harness
    if args.precheck or args.state:
        if args.type != "agent":
            print("--precheck/--state apply to agent jobs only", file=sys.stderr)
            return 1
        if args.precheck:
            job["precheck"] = args.precheck
        if args.state:
            job["state"] = True
    if args.chat:
        if not valid_chat(args.chat):
            print("bad --chat (session id chars: A-Za-z0-9_:-, max 80)", file=sys.stderr)
            return 1
        job["chat"] = args.chat
    if getattr(args, "adaptive", False):
        job["adaptive"] = True
    for k in ("artifact", "gate", "receipt_cmd"):
        if getattr(args, k, None):
            job[k] = getattr(args, k)
    marker = args.start_marker
    if marker:
        job["start_marker"] = marker
    given = [bool(args.at), bool(args.cron), bool(args.every)]
    if sum(given) != 1:
        print("give exactly one of --at / --cron / --every", file=sys.stderr)
        return 1
    if args.at:
        datetime.strptime(args.at, "%Y-%m-%d %H:%M")  # validate
        job["at"] = args.at
    if args.cron:
        job["cron"] = args.cron
        next_from_cron(args.cron, now)  # validate
    if args.every:
        job["every_min"] = parse_every(args.every)
    nxt = compute_next(job, now)
    job["next_run"] = nxt.timestamp() if nxt else None
    job["last_run"] = None
    jobs.append(job)
    save_jobs(jobs)
    print(f"added {args.name!r} → next run: {nxt or 'never (one-shot in the past?)'}")
    return 0


def cmd_list(_args):
    jobs = load_jobs()
    if not jobs:
        print("no jobs")
        return 0
    active = [j for j in jobs if not j.get("retired_at")]
    retired = [j for j in jobs if j.get("retired_at")]
    for j in active:
        nxt = datetime.fromtimestamp(j["next_run"]).strftime("%Y-%m-%d %H:%M") if j.get("next_run") else "-"
        spec = j.get("at") or j.get("cron") or f"every {j.get('every_min')}m"
        roa = " +retire-on-alert" if j.get("retire_on_alert") else ""
        print(f"• {j['name']}  [{j['type']}]  {spec}{roa}  next: {nxt}")
    for j in retired:
        print(f"• {j['name']}  [retired: {j.get('retire_reason','-')} at {j.get('retired_at','-')}]  fired {j.get('fires',0)}x")
    return 0


def cmd_remove(args):
    jobs = load_jobs()
    kept = [j for j in jobs if j["name"] != args.name]
    if len(kept) == len(jobs):
        print(f"no job named {args.name!r}", file=sys.stderr)
        return 1
    save_jobs(kept)
    print(f"removed {args.name!r}")
    return 0


def cmd_chats(_args):
    """Live app chats (open threads with a stream right now). Use an id
    as --chat on add (or in /api/inbox posts) to deliver into that exact
    thread instead of the job thread."""
    try:
        import urllib.request
        token = Path.home().joinpath(".config/terminus/browser/token").read_text().strip()
        req = urllib.request.Request(
            "http://127.0.0.1:9009/api/streams",
            headers={"Authorization": f"Bearer {token}"})
        chats = json.loads(urllib.request.urlopen(req, timeout=10).read()).get("chats", [])
    except Exception as e:
        print(f"streams unavailable ({type(e).__name__})", file=sys.stderr)
        return 1
    if not chats:
        print("no live chats (app closed or no thread open)")
    for c in chats:
        print(f"• {c}")
    return 0


def job_expired(job, now):
    """True if a recurring job passed its end condition (one-shots excluded)."""
    if job.get("at"):
        return False  # one-shots are removed right after firing
    if job.get("until"):
        until = datetime.strptime(job["until"], "%Y-%m-%d") + timedelta(days=1)
        if now >= until:
            return True
    if job.get("max_fires") and (job.get("fires") or 0) >= job["max_fires"]:
        return True
    return False


def retire_job(job, reason):
    """Stamp a job as retired: kept for history, never fires again."""
    job["retired_at"] = datetime.now().isoformat(timespec="seconds")
    job["retire_reason"] = reason


def cmd_run_due(_args):
    os.makedirs(STORE_DIR, exist_ok=True)
    with open(LOCK_FILE, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("run-due already in progress, skipping")
            return 0
        jobs = load_jobs()
        now = datetime.now()
        changed = False
        for job in jobs:
            if job.get("retired_at"):
                continue  # history only — never fires
            if job_expired(job, now):
                notify(job.get("title", job["name"]),
                       f"schedule ended ({job.get('until') or job.get('max_fires')} reached) — retired.")
                retire_job(job, f"ended ({job.get('until') or job.get('max_fires')} reached)")
                changed = True
                continue
            if job.get("next_run") and now.timestamp() >= job["next_run"]:
                alerted = run_job(job)
                job["last_run"] = now.timestamp()
                job["fires"] = (job.get("fires") or 0) + 1
                if alerted and job.get("retire_on_alert"):
                    ts = datetime.now().strftime("%Y-%m-%d %H:%M")
                    with open(FIRED_LOG, "a") as f:
                        f.write(f"[{ts}] {job.get('title', job['name'])}\n"
                                f"retired on alert (first notification sent)\n---\n")
                    print(f"[{ts}] {job.get('title', job['name'])}: retired on alert")
                    retire_job(job, "alert sent")
                elif job.get("at") or job_expired(job, now):
                    if not job.get("at"):
                        notify(job.get("title", job["name"]),
                               "schedule ended — retired.")
                        retire_job(job, "ended")
                    else:
                        retire_job(job, "one-shot fired")
                else:
                    if job.pop("_jumped", False) and job.get("next_run") \
                            and job["next_run"] > now.timestamp():
                        ts = datetime.now().strftime("%Y-%m-%d %H:%M")
                        print(f"[{ts}] {job.get('title', job['name'])}: "
                              f"adaptive jump to {job.get('adaptive_jump')}")
                    else:
                        job.pop("_jumped", False)
                        nxt = compute_next(job, now)
                        job["next_run"] = nxt.timestamp() if nxt else None
                        if job["next_run"] is None:
                            retire_job(job, "no further runs")
                changed = True
        if changed:
            save_jobs(jobs)  # retired jobs kept for history
    return 0


TICKER_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.terminus.schedule</string>
    <key>ProgramArguments</key>
    <array>
        <string>__PYTHON__</string>
        <string>__SKILL__/schedule.py</string>
        <string>run-due</string>
    </array>
    <key>StartInterval</key>
    <integer>60</integer>
    <key>RunAtLoad</key>
    <true/>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>__HOME__/.nvm/versions/node/v22.22.0/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    </dict>
    <key>StandardOutPath</key>
    <string>__STORE__/ticker.log</string>
    <key>StandardErrorPath</key>
    <string>__STORE__/ticker.err.log</string>
</dict>
</plist>
"""


def cmd_install(_args):
    skill_dir = os.path.dirname(os.path.abspath(__file__))
    plist_path = os.path.expanduser("~/Library/LaunchAgents/com.terminus.schedule.plist")
    content = (TICKER_PLIST.replace("__PYTHON__", sys.executable)
               .replace("__SKILL__", skill_dir).replace("__STORE__", STORE_DIR)
               .replace("__HOME__", os.path.expanduser("~")))
    os.makedirs(os.path.dirname(plist_path), exist_ok=True)
    os.makedirs(STORE_DIR, exist_ok=True)
    with open(plist_path, "w") as f:
        f.write(content)
    subprocess.run(["launchctl", "unload", plist_path], capture_output=True)
    r = subprocess.run(["launchctl", "load", plist_path], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr.strip(), file=sys.stderr)
        return 1
    print(f"ticker installed + loaded ({plist_path})")
    return 0


def main():
    ap = argparse.ArgumentParser(prog="schedule.py", description="terminus schedule — local cron")
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("add", help="add a job")
    a.add_argument("--name", required=True)
    a.add_argument("--type", required=True, choices=["remind", "exec", "agent"])
    a.add_argument("--payload", required=True)
    a.add_argument("--title", default=None)
    a.add_argument("--at", default=None, help='"YYYY-MM-DD HH:MM" one-shot')
    a.add_argument("--cron", default=None, help='"M H DOM MON DOW" repeat')
    a.add_argument("--every", default=None, help="10m, 1h, 1d …")
    a.add_argument("--until", default=None, help='"YYYY-MM-DD" stop repeat after this date')
    a.add_argument("--count", default=None, type=int, help="stop after N fires")
    a.add_argument("--retire-on-alert", action="store_true",
                   help="retire automatically on first non-SILENT alert")
    a.add_argument("--start-marker", default=None,
                   help="strip agent chatter above first line starting with this")
    a.add_argument("--adaptive", action="store_true",
                   help="honour trailing NEXT_RUN=YYYY-MM-DD HH:MM to skip quiet days")
    a.add_argument("--artifact", default=None,
                   help="sealed delivery: file the agent must write; its bytes are delivered, agent prose never is ({date} ok)")
    a.add_argument("--gate", default=None,
                   help="command that must exit 0 for the artifact to deliver ({file}, {date} substituted)")
    a.add_argument("--receipt-cmd", dest="receipt_cmd", default=None,
                   help="deliver this command's first stdout line instead of the artifact")
    a.add_argument("--priority", default="default",
                   choices=["min", "low", "default", "high", "urgent"])
    a.add_argument("--harness", default="claude",
                   choices=["auto", "opencode", "codex", "claude"],
                   help="agent backend (default claude, Haiku)")
    a.add_argument("--precheck", default=None,
                   help="agent jobs: command run first; empty stdout = skip the model this tick, output = added to the prompt, "
                        "non-zero exit = failure alert. $SCHEDULE_STATE names the job's state file")
    a.add_argument("--state", action="store_true",
                   help="agent jobs: keep a small JSON state file (8 KB cap) the agent reads and rewrites each run")
    a.add_argument("--chat", default=None,
                   help="deliver into this session thread instead of the job thread")

    sub.add_parser("list", help="list jobs")
    sub.add_parser("chats", help="list live app chats (targets for --chat)")
    r = sub.add_parser("remove", help="remove a job")
    r.add_argument("name")
    sub.add_parser("run-due", help="fire due jobs (ticker calls this)")
    sub.add_parser("install", help="install per-minute ticker")
    sub.add_parser("setup", help="retired")
    n = sub.add_parser("notify", help="retired (use a one-shot remind job)")
    n.add_argument("--template", required=True,
                   choices=["ping", "reminder", "standup", "price", "done", "alert"])
    n.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")

    args = ap.parse_args()
    if args.cmd == "add":
        return cmd_add(args)
    if args.cmd == "list":
        return cmd_list(args)
    if args.cmd == "chats":
        return cmd_chats(args)
    if args.cmd == "remove":
        return cmd_remove(args)
    if args.cmd == "run-due":
        return cmd_run_due(args)
    if args.cmd == "install":
        return cmd_install(args)
    if args.cmd == "setup":
        return cmd_setup(args)
    if args.cmd == "notify":
        print("notify is retired: add a one-shot remind job — it fires into the Terminus inbox.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
