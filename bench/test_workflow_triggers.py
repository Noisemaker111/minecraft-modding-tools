"""A pull request pays for its cloud checks once, and nothing is checked twice (.github/workflows/).
- A workflow that checks pull requests starts on ready_for_review and every one of its jobs is skipped on a draft: a
  draft's pushes are proven on the agent's own machine (python bench/run_tests.py), and each used to start every job.
- Such a workflow runs on a push to main only for named files (the files of its cache keys, to save the caches main
  alone can save), never for a folder: main takes only pull requests that are up to date with it, so the merged tree is
  the tree the pull request's run already passed.
- The names main requires (linux, windows) are never reported for a draft: GitHub counts a skipped job as passed, and
  lists one whose name is an expression under the expression's text.
The rules are tried on workflows written here that break each one, so a rule that stopped reading the files fails too.
Run: python bench/test_workflow_triggers.py"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIRED = ("linux", "windows")  # main's ruleset "main: green checks only"
DRAFT = "github.event.pull_request.draft"
checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def block(text, key, indent):
    """The lines under `key:` at this indent, to the next line indented no deeper."""
    found = re.search(rf"^{' ' * indent}{re.escape(key)}:[^\n]*\n((?:(?:{' ' * (indent + 1)}[^\n]*|[ \t]*)\n?)*)", text, re.M)
    return None if found is None else found.group(0)


def problems(name, text):
    """What a workflow's triggers get wrong, each said with what to do instead."""
    on = block(text, "on", 0) or ""
    pull = block(on, "pull_request", 2)
    if pull is None:
        return []
    out = []
    types = re.search(r"types:\s*\[([^\]]*)\]", pull)
    named = {t.strip() for t in types.group(1).split(",")} if types else set()
    if not {"ready_for_review", "synchronize", "opened", "reopened"} <= named:
        out.append(f"{name}: its pull_request trigger needs types: [opened, reopened, ready_for_review, synchronize], or marking a pull request ready starts nothing")
    jobs = block(text, "jobs", 0) or ""
    for job in re.findall(r"^  ([\w-]+):\s*$", jobs, re.M):
        body = block(jobs, job, 2)
        gate = re.search(r"^    if:(.*)$", body, re.M)
        if "needs:" not in body and (gate is None or f"!{DRAFT}" not in gate.group(1)):
            out.append(f"{name}: job {job} runs on a draft's pushes; give it `if: ${{{{ !{DRAFT} }}}}` (a draft is proven with python bench/run_tests.py, and marking it ready starts the job)")
        title = re.search(r"^    name:(.*)$", body, re.M)
        if job in REQUIRED and (title is None or DRAFT not in title.group(1)):
            out.append(f"{name}: job {job} is a check main requires, and skipped on a draft it would count as passed; name it another way on a draft (name: ${{{{ {DRAFT} && 'draft, {job} not run' || '{job}' }}}})")
    push = block(on, "push", 2)
    if push is not None:
        paths = re.findall(r"'([^']+)'", (block(push, "paths", 4) or "") + "".join(re.findall(r"^    paths:\s*\[[^\]]*\]", push, re.M)))
        keyed = {f.removeprefix("portable workspace/") for call in re.findall(r"hashFiles\(([^)]*)\)", text) for f in re.findall(r"'([^']+)'", call)}
        wide = sorted({p for p in paths if "*" in p or p.endswith("/")})
        if not paths or wide:
            out.append(f"{name}: it runs on a merge to main" + (f" for {', '.join(wide)}" if wide else "") + ", on the tree its pull request just passed; a push to main may start it only for the named files of its cache keys (paths:), to save the caches")
        elif keyed and set(paths) != keyed:
            out.append(f"{name}: its push paths and the files of its cache keys differ ({', '.join(sorted(set(paths) ^ keyed))}); a push to main runs it to save the caches, so it starts on exactly the files hashFiles reads")
        if "event_name == 'push'" in text:
            out.append(f"{name}: a cache saved only on a push cannot be saved by hand; save it when github.ref is refs/heads/main")
    return out


GOOD = """name: CI
on:
  push:
    branches: [main]
    paths:
      - 'fixtures/build.gradle'
  pull_request:
    types: [opened, reopened, ready_for_review, synchronize]
  workflow_dispatch:
jobs:
  linux:
    name: ${{ github.event.pull_request.draft && 'draft, linux not run' || 'linux' }}
    if: ${{ !github.event.pull_request.draft }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/cache/restore@v4
        with:
          key: ci-${{ hashFiles('fixtures/build.gradle') }}
  report:
    needs: [linux]
    runs-on: ubuntu-latest
"""

check(problems("good.yml", GOOD) == [], f"a workflow that follows every rule passes: {problems('good.yml', GOOD)}")
check(problems("dispatch.yml", "on:\n  workflow_dispatch:\njobs:\n  sweep:\n    runs-on: ubuntu-latest\n") == [], "a workflow that checks no pull request is left alone")
for label, broken, said in [
    ("no types", GOOD.replace("    types: [opened, reopened, ready_for_review, synchronize]\n", ""), "marking a pull request ready starts nothing"),
    ("no ready_for_review", GOOD.replace("ready_for_review, ", ""), "marking a pull request ready starts nothing"),
    ("a job with no draft gate", GOOD.replace("    if: ${{ !github.event.pull_request.draft }}\n", ""), "job linux runs on a draft's pushes"),
    ("a gate that lets drafts through", GOOD.replace("if: ${{ !github.event.pull_request.draft }}", "if: ${{ github.event.pull_request.draft || true }}"), "job linux runs on a draft's pushes"),
    ("a required name on a draft", GOOD.replace("    name: ${{ github.event.pull_request.draft && 'draft, linux not run' || 'linux' }}\n", ""), "would count as passed"),
    ("every push to main", GOOD.replace("    paths:\n      - 'fixtures/build.gradle'\n", "", 1), "it runs on a merge to main, on the tree"),
    ("a folder on main", GOOD.replace("      - 'fixtures/build.gradle'\n", "      - 'fixtures/**'\n", 1), "for fixtures/**"),
    ("paths that are not the cache key's", GOOD.replace("      - 'fixtures/build.gradle'\n", "      - 'fixtures/settings.gradle'\n", 1), "differ (fixtures/build.gradle, fixtures/settings.gradle)"),
    ("a cache saved only on a push", GOOD + "      - if: github.event_name == 'push'\n", "cannot be saved by hand"),
]:
    got = problems("broken.yml", broken)
    check(len(got) == 1 and said in got[0], f"{label} is refused once, with what to do: {got}")

workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
checking = [w for w in workflows if block(block(w.read_text(encoding="utf-8"), "on", 0) or "", "pull_request", 2) is not None]
check(len(checking) >= 4, f"the workflows that check pull requests are read: {[w.name for w in checking]}")
for wf in workflows:
    for problem in problems(wf.name, wf.read_text(encoding="utf-8")):
        check(False, problem)
    checks += 1

print(f"workflow triggers: {checks} checks passed")
