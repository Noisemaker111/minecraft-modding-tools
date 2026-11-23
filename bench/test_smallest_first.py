"""bench/smallest_first.py: every wide cloud run waits on its smallest real case, and its plan refuses the setup that
ended hours of runs on 2026-11-23 (a comma in a cache key, an import a file split had moved) before any job starts."""
import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smallest_first as S  # noqa: E402

checks = 0


def check(ok, what):
    global checks
    if not ok:
        print("FAIL:", what)
        sys.exit(1)
    checks += 1


workflows = {p.name: S.load_workflow(p) for p in sorted(S.WORKFLOWS.glob("*.yml"))}
for name in ("cloud-exposure.yml", "cloud-load.yml", "cloud-ports.yml", "cloud-packs.yml", "cloud-convert.yml", "stonecutter-nodes.yml"):
    check(S.wide_jobs(workflows[name]), f"{name} has a matrix from its plan, so the check covers it")
for name, wf in workflows.items():
    problems = S.order_problems(wf) + S.entry_point_problems(wf)
    check(not problems, f"{name}: " + "\n".join(problems))

# A matrix that does not wait on its first job, or a first job whose steps drifted from the matrix's, fails.
exposure = workflows["cloud-exposure.yml"]
loose = copy.deepcopy(exposure)
loose["jobs"]["update"]["needs"] = ["plan"]
check(any("nothing proven first" in p for p in S.order_problems(loose)), "a matrix that starts without its first job fails the check")
drifted = copy.deepcopy(exposure)
drifted["jobs"]["update-first"]["steps"] = drifted["jobs"]["update-first"]["steps"][:-1]
check(any("nothing proven first" in p for p in S.order_problems(drifted)), "a first job whose steps differ from the matrix's proves nothing for it")
renv = copy.deepcopy(exposure)
renv["jobs"]["update-first"]["env"] = {"UPDATE": "x"}
check(any("other env" in p for p in S.order_problems(renv)), "a first job with other env fails")
pr_only = copy.deepcopy(loose)
pr_only["on"] = {"pull_request": {}}
check(not S.order_problems(pr_only), "a pull request's own check (port-check.yml) is sized to its change and is not a sweep")

# Cache keys: every entry of the real plan passes; the scoreboard's comma key of 2026-11-23 fails before any job.
updates = json.loads(subprocess.run([sys.executable, str(S.ROOT / "bench" / "exposure.py"), "updates"], capture_output=True, text=True, check=True).stdout)
check(len(updates) > 100 and not S.key_problems(exposure, updates), "every update's cache keys are valid")
comma = S.key_problems(exposure, ["fabric:1.20.1:1.21,1.21.1"])
check(comma and all("comma" in p for p in comma), f"a job of two targets named FROM:TO1,TO2 makes a key GitHub refuses: {comma}")
check(any("over GitHub's 512" in p for p in S.key_problems(exposure, ["fabric:1.20.1:" + "+".join(["1.21"] * 120)])), "a key over 512 characters fails")
# The fix 1804b97a made, a key from a step's output, is one the plan cannot see for each entry: refused, with what to do.
hidden = copy.deepcopy(exposure)
for step in hidden["jobs"]["update"]["steps"]:
    if step.get("uses", "").startswith("actions/cache"):
        step["with"]["key"] = "${{ steps.release.outputs.job }}-${{ github.run_id }}"
check(any("build the key from matrix values" in p for p in S.key_problems(hidden, updates[:1])), "a key the plan cannot work out is refused")
# A key inside a local composite action is checked with the inputs the step gives it.
(S.ROOT / ".runs").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=S.ROOT / ".runs") as d:
    action = Path(d)
    (action / "action.yml").write_text("runs:\n  using: composite\n  steps:\n    - uses: actions/cache/restore@v4\n      with:\n        key: k-${{ inputs.what }}\n", encoding="utf-8")
    wf = {"on": {"workflow_dispatch": {}}, "jobs": {"m": {"strategy": {"matrix": {"u": "${{ fromJSON(needs.plan.outputs.rest) }}"}},
                                                          "steps": [{"uses": "./" + action.relative_to(S.ROOT).as_posix(), "with": {"what": "${{ matrix.u }}"}}]}}}
    check(S.key_problems(wf, ["a,b"]) and not S.key_problems(wf, ["a+b"]), "a composite action's key is worked out from the matrix value it is given")

# Entry points: an import of a name a module no longer has fails, in any job's inline Python or in a script it runs.
def wf_running(script):
    return {"on": {"workflow_dispatch": {}}, "jobs": {"summary": {"steps": [{"run": script}]}}}


real = "python -c \"import sys; sys.path.insert(0, 'bench'); from portboard.gameload import load_plan; print(load_plan)\""
check(not S.entry_point_problems(wf_running(real)), "an import of a name that is there passes")
moved = real.replace("load_plan", "plan_load")
check(any("has no plan_load" in p for p in S.entry_point_problems(wf_running(moved))), "an import a file split moved fails")
heredoc = "python3 - <<'EOF' >> \"$GITHUB_OUTPUT\"\nimport sys\nsys.path.insert(0, 'bench')\nfrom portboard import nosuchmodule\nEOF\n"
check(any("nosuchmodule" in p for p in S.entry_point_problems(wf_running(heredoc))), "a heredoc's import of a missing module fails")
check(any("not there" in p for p in S.entry_point_problems(wf_running("python bench/no_such_script.py run"))), "a script that is not there fails")
# Followed into the repository's own modules: a lazy import inside a function of a script the workflow runs.
with tempfile.TemporaryDirectory(dir=S.ROOT / "bench") as d:
    script = Path(d) / "entry.py"
    script.write_text("import sys\n\ndef later():\n    from portboard.board import no_such_name\n", encoding="utf-8")
    rel = script.relative_to(S.ROOT).as_posix()
    check(any("has no no_such_name" in p for p in S.entry_point_problems(wf_running(f"python {rel} x"))), "an import inside a function of a called script is followed")

# The split: the smallest entry runs first, the others after, none twice; the scoreboard's first is a Fabric update
# within one release family.
first, rest = S.split(updates, S.update_entry_size)
check(first not in rest and len(rest) == len(updates) - 1, "the first entry is not run again in the matrix")
loader, frm, to = first.split(":")
check(loader == "fabric" and S.version(frm)[:2] == S.version(to)[:2], f"the scoreboard starts with a small update: {first}")
check(S.split(["neoforge:1.21.1:26.3", "neoforge:1.21.1:1.21.2"], S.update_entry_size)[0] == "neoforge:1.21.1:1.21.2", "a jump is not the smallest")
with tempfile.TemporaryDirectory() as d:
    out = Path(d) / "output"
    S.plan(["neoforge:1.21.1:1.21.2"], S.update_entry_size, "test", workflow=S.WORKFLOWS / "cloud-load.yml", out=out)
    check(out.read_text(encoding="utf-8") == 'first=["neoforge:1.21.1:1.21.2"]\nrest=[]\n', "a one-update proof runs as the first job alone")
try:
    S.plan(["fabric:1.20.1:1.21,1.21.1"], S.update_entry_size, "test", workflow=S.WORKFLOWS / "cloud-exposure.yml", out=Path(tempfile.gettempdir()) / "unused")
    check(False, "a plan with a key GitHub refuses stops")
except SystemExit as e:
    check("comma" in str(e), "the plan step stops on the comma key, before any job starts")

print(f"smallest_first: {checks} checks passed")
