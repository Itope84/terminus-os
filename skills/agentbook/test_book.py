import os, subprocess, sys, tempfile, unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def run(script, *args, env=None, stdin=""):
    r = subprocess.run([sys.executable, str(HERE / script), *args], input=stdin, text=True, capture_output=True, env={**os.environ, **env})
    return r.returncode, r.stdout, r.stderr


class Book(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.env = {"AGENTBOOK_HOME": self.dir, "TERMINUS_AGENTBOOK": "claude/c1", "AGENTBOOK_TODAY": "2026-11-01"}

    def w(self, *a, **kw):
        return run("write.py", *a, env={**self.env, **kw.get("env", {})})

    def test_lines_expire_by_section_and_touch_renews(self):
        self.w("set", "goal", "g")
        self.w("add", "open", "old task")
        self.w("add", "decisions", "keep me")
        late = {"AGENTBOOK_TODAY": "2026-11-20"}  # 19 days on: open (14) gone, decisions (45) stay, goal never
        code, out, _ = self.w("add", "refs", "r", env=late)
        self.assertEqual(code, 0)
        self.assertIn("expired 1 old line", out)
        _, shown, _ = run("read.py", env={**self.env, **late})
        self.assertNotIn("old task", shown)
        self.assertIn("keep me (19d)", shown)
        self.assertIn("1. g (19d)", shown)

    def test_touch_restarts_the_clock(self):
        self.w("add", "open", "task")
        self.w("touch", "open", "1", env={"AGENTBOOK_TODAY": "2026-11-12"})
        _, shown, _ = run("read.py", env={**self.env, "AGENTBOOK_TODAY": "2026-11-20"})
        self.assertIn("task (8d)", shown)

    def test_other_chat_is_unreachable(self):
        self.w("add", "open", "mine")
        code, _, err = run("read.py", env={**self.env, "TERMINUS_AGENTBOOK": "claude/../c2"})
        self.assertNotEqual(code, 0)
        _, shown, _ = run("read.py", env={**self.env, "TERMINUS_AGENTBOOK": "claude/c2"})
        self.assertNotIn("mine", shown)



class Fit(unittest.TestCase):
    def test_over_cap_drops_oldest_and_keeps_the_new_line(self):
        d = tempfile.mkdtemp()
        env = {"AGENTBOOK_HOME": d, "TERMINUS_AGENTBOOK": "claude/c1"}
        for day in ("2026-11-01", "2026-11-02", "2026-11-03"):
            code, _, err = run("write.py", "add", "decisions", f"{day} " + "x" * 140, env={**env, "AGENTBOOK_TODAY": day})
            self.assertEqual(code, 0, err)
        outs = []
        for day in ("2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08"):
            outs.append(run("write.py", "add", "decisions", f"{day} " + "y" * 140, env={**env, "AGENTBOOK_TODAY": day})[1])
        self.assertTrue(any("dropped oldest to fit: [decisions] 2026-11-01 xxx" in o for o in outs), outs)
        code, out, _ = run("write.py", "add", "decisions", "newest " + "z" * 100, env={**env, "AGENTBOOK_TODAY": "2026-11-09"})
        self.assertEqual(code, 0)
        _, shown, _ = run("read.py", env={**env, "AGENTBOOK_TODAY": "2026-11-09"})
        self.assertIn("newest", shown)
        self.assertNotIn("2026-11-01 xxx", shown)
        self.assertIn("2026-11-02 xxx", shown)

    def test_goal_is_never_dropped(self):
        d = tempfile.mkdtemp()
        env = {"AGENTBOOK_HOME": d, "TERMINUS_AGENTBOOK": "claude/c1", "AGENTBOOK_TODAY": "2026-11-01"}
        run("write.py", "set", "goal", "keep", env=env)
        for sec in ("decisions", "open", "refs", "constraints"):
            for n in range(6):
                run("write.py", "add", sec, f"{sec}{n} " + "q" * 130, env=env)
        _, shown, _ = run("read.py", env=env)
        self.assertIn("1. keep", shown)
        total = int(shown.rsplit("[", 1)[1].split("/")[0])
        self.assertLessEqual(total, 3200)


if __name__ == "__main__":
    unittest.main()
