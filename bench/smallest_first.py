"""Smallest first: a wide cloud run proves its setup on one real case before its matrix starts.

    python bench/smallest_first.py updates '["fabric:1.21.1:1.21.2", ...]'   a plan step: split LOADER:FROM:TO entries
    python bench/smallest_first.py check [WORKFLOW ...]                     what the plan checks that needs no entries

On 2026-11-23 hours of cloud runs died on setup that one small job would have shown in two minutes: a 73-job scoreboard
on a comma in a cache key (GitHub refuses the key before any port runs), a step importing a function a file split had
moved, a server and a client racing on one install folder. Each showed only after the whole matrix had started.

So every cloud workflow a person or a release starts (cloud-*.yml) runs its matrix in two jobs with the same steps (a
YAML anchor on the matrix job's steps, aliased by the first job, so the two cannot drift): FIRST runs the one entry
its plan picks as the smallest real case, and the matrix runs the rest only after it passed. FIRST is a real entry, not
a rehearsal: its result joins the summary like any other, so the order costs no runner time, only the smallest case's
minutes of wall time. bench/test_smallest_first.py fails when a workflow's matrix does not wait on such a job.

Before either starts, plan() checks what one entry cannot show for the others, in seconds:
- every cache key and restore key each entry's job will use, against the rules GitHub applies (no comma, at most 512
  characters, at most 10 keys), worked out from the workflow's own key expressions and the entry's matrix values;
- every Python import of every script the workflow runs, in any job (the summary's too), followed into the repository's
  own modules: a name a module no longer defines fails here, not after the matrix ran.
"""
import ast
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
# What @actions/cache's checkKey refuses (actions/toolkit packages/cache/src/cache.ts): a key or restore key longer than
# 512 characters or holding a comma, and more than 10 of them in one step.
KEY_LENGTH = 512
KEY_COUNT = 10
# What a sweep's run is started by. A workflow started only by a pull request (port-check.yml) is sized to the change.
STARTED = {"workflow_dispatch", "push", "schedule", "workflow_call", "release", "repository_dispatch"}


def load_workflow(path):
    """A workflow file as data, its anchors resolved. PyYAML where it is installed, else yq (on GitHub's runners)."""
    text = Path(path).read_text(encoding="utf-8")
    try:
        import yaml
    except ImportError:
        done = subprocess.run(["yq", "-o=json", "explode(.)", str(path)], capture_output=True, text=True)
        if done.returncode:
            raise SystemExit(f"reading {path} needs PyYAML (python -m pip install pyyaml) or yq: {done.stderr.strip()}")
        return json.loads(done.stdout)
    data = yaml.safe_load(text)
    # YAML 1.1 reads the key `on` as true.
    if True in data:
        data["on"] = data.pop(True)
    return data


def triggers(wf):
    on = wf.get("on") or {}
    return {on} if isinstance(on, str) else set(on)


def dynamic_matrix(job):
    """The matrix variable and its expression when a job's matrix comes from another job's output, else None."""
    matrix = (job.get("strategy") or {}).get("matrix")
    if isinstance(matrix, str) and "fromJSON(" in matrix:
        return "include", matrix
    for name, value in (matrix or {}).items() if isinstance(matrix, dict) else ():
        if isinstance(value, str) and "fromJSON(" in value:
            return name, value
    return None


def needs(job):
    n = job.get("needs") or []
    return [n] if isinstance(n, str) else list(n)


def wide_jobs(wf):
    """The matrix jobs of a workflow a person or a release starts: (job id, job) for each."""
    if not triggers(wf) & STARTED:
        return []
    return [(name, job) for name, job in (wf.get("jobs") or {}).items() if dynamic_matrix(job)]


def order_problems(wf):
    """What is wrong with how a workflow orders its matrix: each wide job must wait on a job of the same steps that runs
    the plan's first entry, and run only the rest."""
    jobs = wf.get("jobs") or {}
    out = []
    for name, job in wide_jobs(wf):
        var, expr = dynamic_matrix(job)
        if ".outputs.first" in expr:
            continue
        firsts = [n for n in needs(job) if n in jobs and jobs[n].get("steps") == job.get("steps") and dynamic_matrix(jobs[n])]
        if not firsts:
            out.append(f"job {name}: its matrix ({expr}) starts with nothing proven first. Give it a job with the same steps (steps: &{name}-steps "
                       f"here, steps: *{name}-steps there) whose matrix is the plan's first entry, and list that job in {name}'s needs "
                       f"(bench/smallest_first.py)")
            continue
        first = jobs[firsts[0]]
        fvar, fexpr = dynamic_matrix(first)
        if fvar != var or ".outputs.first" not in fexpr or ".outputs.rest" not in expr:
            out.append(f"job {name}: its matrix must be the plan's rest ({expr}) and {firsts[0]}'s the plan's first ({fexpr}), over the same variable")
        if first.get("env") != job.get("env"):
            out.append(f"job {name}: {firsts[0]} runs with other env than it does; share it (env: &{name}-env / env: *{name}-env)")
    return out


# ---- which entry is smallest ----

def version(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def update_size(loader, frm, to):
    """How big a port of a loader's mods from one release to another is, smallest first: Fabric (no decompiling), a
    forward update within one release family (1.16.4 -> 1.16.5, the fewest changes), then the oldest game (the
    smallest jars). Measured on main's sweep 45193121517: such updates took 76 to 132 s, the biggest 1016 s."""
    f, t = version(frm), version(to)
    return (loader != "fabric", not (f[:2] == t[:2] and t > f), t < f, f, t)


def update_entry_size(entry):
    loader, frm, to = entry.split(":")
    return update_size(loader, frm, to)


# ---- cache keys ----

EXPR = re.compile(r"\$\{\{\s*(.*?)\s*\}\}")
# The longest each value GitHub puts in a key can be, so a key's length is checked at its worst.
PLACEHOLDERS = {"github.run_id": "9" * 11, "github.run_attempt": "99", "github.run_number": "99999", "github.sha": "f" * 40,
                "strategy.job-index": "999", "runner.os": "Windows", "runner.arch": "ARM64"}


class Unknown(Exception):
    pass


def expand(text, values):
    """A key expression with every ${{ }} replaced by its value for one entry. values: matrix.NAME and inputs.NAME
    (a composite action's) to their text; GitHub's run ids and hashFiles() at their longest. Anything else the plan
    cannot know for every entry, so it is refused rather than passed unchecked."""
    def one(m):
        expr = m.group(1)
        if expr in values:
            return values[expr]
        if expr in PLACEHOLDERS:
            return PLACEHOLDERS[expr]
        if re.fullmatch(r"hashFiles\(.*\)", expr):
            return "f" * 64
        raise Unknown(expr)
    return EXPR.sub(one, str(text))


def cache_steps(steps, values, where):
    """(where, key text, restore keys text, values) of every actions/cache step, following local composite actions."""
    for i, step in enumerate(steps or []):
        uses = step.get("uses") or ""
        label = f"{where} step {step.get('name') or uses or i + 1}"
        if uses.startswith("actions/cache"):
            yield label, step.get("with") or {}, values
        elif uses.startswith("./"):
            action = load_workflow(ROOT / uses / "action.yml")
            given = {}
            for name, value in (step.get("with") or {}).items():
                given[f"inputs.{name}"] = expand(value, values)
            for name, spec in ((action.get("inputs") or {}).items()):
                if f"inputs.{name}" not in given and isinstance(spec, dict) and "default" in spec:
                    given[f"inputs.{name}"] = str(spec["default"])
            yield from cache_steps((action.get("runs") or {}).get("steps"), {**values, **given}, f"{label} ({uses})")


def key_problems(wf, entries):
    """Each cache key a wide job would refuse, for every entry of the plan: GitHub checks a key only when the job using
    it runs, so a bad one ended a whole matrix of jobs that each set up first."""
    out = []
    for name, job in wide_jobs(wf):
        var, expr = dynamic_matrix(job)
        if ".outputs.first" in expr:
            continue  # the same steps as the job that runs the rest
        for entry in entries:
            if isinstance(entry, dict):
                prefix = "matrix." if var == "include" else f"matrix.{var}."
                values = {prefix + k: str(v) for k, v in entry.items()}
            else:
                values = {f"matrix.{var}": str(entry)}
            values["github.job"] = name
            for where, given, scope in cache_steps(job.get("steps"), values, f"job {name}"):
                try:
                    keys = [expand(given.get("key", ""), scope)]
                    keys += [line.strip() for line in expand(given.get("restore-keys", ""), scope).splitlines() if line.strip()]
                except Unknown as e:
                    out.append(f"{where}: the cache key uses ${{{{ {e} }}}}, which the plan cannot work out for every entry; build the key "
                               f"from matrix values (the plan can give each entry one)")
                    break
                for key in keys:
                    if "," in key:
                        out.append(f"{where}, entry {entry}: cache key {key!r} holds a comma, which GitHub refuses")
                    if len(key) > KEY_LENGTH:
                        out.append(f"{where}, entry {entry}: cache key {key[:60]!r}... is {len(key)} characters, over GitHub's {KEY_LENGTH}")
                if len(keys) > KEY_COUNT:
                    out.append(f"{where}, entry {entry}: {len(keys)} cache keys, over GitHub's {KEY_COUNT}")
    return list(dict.fromkeys(out))


# ---- imports of the scripts a workflow runs ----

PY_FILE = re.compile(r"\bpython3?(?:\s+-[A-Za-z]+)*\s+((?:\./)?(?:bench|tools)/[\w./-]+\.py)\b")
HEREDOC = re.compile(r"\bpython3?\s+-(?:[ \t]+[^\n<]*?)?[ \t]*<<-?[ \t]*['\"]?(\w+)['\"]?[^\n]*\n(.*?)\n[ \t]*\1[ \t]*$", re.S | re.M)
INLINE = re.compile(r"\bpython3?\s+-c\s+(\"(?:[^\"\\]|\\.)*\"|'[^']*')")


def run_scripts(steps, where):
    """(where, shell script) of every run step, following local composite actions."""
    for i, step in enumerate(steps or []):
        label = f"{where} step {step.get('name') or step.get('uses') or i + 1}"
        if "run" in step:
            yield label, str(step["run"])
        uses = step.get("uses") or ""
        if uses.startswith("./"):
            action = load_workflow(ROOT / uses / "action.yml")
            yield from run_scripts((action.get("runs") or {}).get("steps"), f"{label} ({uses})")


def python_calls(wf):
    """(where, script file or None, inline source or None) of every Python a workflow runs."""
    for name, job in (wf.get("jobs") or {}).items():
        for where, script in run_scripts(job.get("steps"), f"job {name}"):
            for m in PY_FILE.finditer(script):
                yield where, ROOT / m.group(1).removeprefix("./"), None
            for m in HEREDOC.finditer(script):
                yield where, None, m.group(2)
            for m in INLINE.finditer(script):
                quoted = m.group(1)
                yield where, None, quoted[1:-1].replace('\\"', '"') if quoted[0] == '"' else quoted[1:-1]


def find_module(name, roots):
    for root in roots:
        base = root.joinpath(*name.split("."))
        if base.with_suffix(".py").is_file():
            return base.with_suffix(".py")
        if (base / "__init__.py").is_file():
            return base / "__init__.py"
        if base.is_dir() and any(base.glob("*.py")):
            return base
    return None


def defined(tree):
    """The names a module defines at its top level, or None when it can define any (a star import, __getattr__)."""
    names = set()

    def walk(body):
        for node in body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                names.add(node.name)
                if node.name == "__getattr__":
                    return False
            elif isinstance(node, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
                for target in node.targets if isinstance(node, ast.Assign) else [node.target]:
                    names.update(n.id for n in ast.walk(target) if isinstance(n, ast.Name))
            elif isinstance(node, (ast.Import, ast.ImportFrom)):
                for alias in node.names:
                    if alias.name == "*":
                        return False
                    names.add(alias.asname or alias.name.split(".")[0])
            elif isinstance(node, (ast.If, ast.Try, ast.With, ast.For, ast.While)):
                for part in ("body", "orelse", "finalbody"):
                    if walk(getattr(node, part, []) or []) is False:
                        return False
                for handler in getattr(node, "handlers", []):
                    if walk(handler.body) is False:
                        return False
        return True
    return names if walk(tree.body) else None


def guarded(tree):
    """The import nodes inside a try that catches their failure: optional, not required."""
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Try) and any(h.type is None or any(isinstance(n, ast.Name) and n.id in ("ImportError", "ModuleNotFoundError", "Exception")
                                                                   for n in ast.walk(h.type)) for h in node.handlers):
            for part in node.body:
                out.update(id(n) for n in ast.walk(part) if isinstance(n, (ast.Import, ast.ImportFrom)))
    return out


def import_problems(source, path, roots, seen):
    """Each import in a piece of Python (and, followed, in the repository's own modules it imports) that would fail."""
    try:
        tree = ast.parse(source, filename=str(path or "<inline>"))
    except SyntaxError as e:
        return [f"{path or 'inline Python'}: does not parse ({e.msg}, line {e.lineno}); a script the plan cannot read cannot be checked"]
    optional = guarded(tree)
    out = []
    here = [path.parent] if path else []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Import, ast.ImportFrom)) or id(node) in optional:
            continue
        if isinstance(node, ast.ImportFrom) and node.level:
            package = path.parent
            for _ in range(node.level - 1):
                package = package.parent
            wanted = [(".".join(filter(None, [node.module])), [a.name for a in node.names], [package])]
        elif isinstance(node, ast.ImportFrom):
            wanted = [(node.module, [a.name for a in node.names], here + roots)]
        else:
            wanted = [(a.name, [], here + roots) for a in node.names]
        for module, names, where in wanted:
            found = find_module(module, where) if module else (where[0] / "__init__.py" if (where[0] / "__init__.py").is_file() else where[0])
            if found is None:
                if importlib.util.find_spec(module.split(".")[0]) is None:
                    out.append(f"{path or 'inline Python'}:{node.lineno}: no module {module}")
                continue
            if found.is_dir():
                for n in names:
                    if find_module(n, [found]) is None:
                        out.append(f"{path or 'inline Python'}:{node.lineno}: {module} has no {n}")
                continue
            tree_of = ast.parse(found.read_text(encoding="utf-8"), filename=str(found))
            have = defined(tree_of)
            for n in names:
                if n != "*" and have is not None and n not in have and find_module(n, [found.parent]) is None:
                    out.append(f"{path or 'inline Python'}:{node.lineno}: {module} ({found.relative_to(ROOT).as_posix()}) has no {n}")
            if found not in seen:
                seen.add(found)
                out += import_problems(found.read_text(encoding="utf-8"), found, roots, seen)
    return out


def entry_point_problems(wf):
    """Each script a workflow runs that is missing, or imports a module or a name that is not there."""
    roots = [ROOT / "bench", ROOT / "tools", ROOT]
    out, seen = [], set()
    for where, script, inline in python_calls(wf):
        if script is not None:
            if not script.is_file():
                out.append(f"{where}: runs {script.relative_to(ROOT).as_posix()}, which is not there")
            elif script not in seen:
                seen.add(script)
                out += [f"{where}: {p}" for p in import_problems(script.read_text(encoding="utf-8"), script, roots, seen)]
        else:
            out += [f"{where}: {p}" for p in import_problems(inline, None, roots, seen)]
    return list(dict.fromkeys(out))


# ---- the plan step ----

def this_workflow():
    """The workflow file of the running job, from GITHUB_WORKFLOW_REF (OWNER/REPO/.github/workflows/F.yml@REF)."""
    ref = os.environ.get("GITHUB_WORKFLOW_REF", "")
    m = re.search(r"(\.github/workflows/[^@]+)@", ref)
    if not m:
        raise SystemExit("not in a GitHub Actions job (no GITHUB_WORKFLOW_REF): pass the workflow file")
    return ROOT / m.group(1)


def split(entries, size):
    """(first, rest): the smallest entry, then every other in the plan's own order."""
    if not entries:
        raise SystemExit("the plan has no entry to run")
    at = min(range(len(entries)), key=lambda i: size(entries[i]))
    return entries[at], entries[:at] + entries[at + 1:]


def plan(entries, size, why, workflow=None, out=None):
    """A plan step's last act: checks every entry's cache keys and the workflow's imports, then writes first= (the
    smallest entry, as a one-entry JSON list) and rest= (the others) to GITHUB_OUTPUT, and says which is first and why."""
    path = Path(workflow) if workflow else this_workflow()
    wf = load_workflow(path)
    problems = key_problems(wf, entries) + entry_point_problems(wf)
    if problems:
        if out is None and os.environ.get("GITHUB_ACTIONS"):
            for p in problems:
                print(f"::error::{p}")
        raise SystemExit(f"{len(problems)} problems in {path.name}'s setup, found before any job started:\n" + "\n".join(problems))
    first, rest = split(entries, size)
    label = first if isinstance(first, str) else json.dumps(first)
    line = f"Smallest first: {label} ({why}); then the other {len(rest)} once it passes."
    print(line, file=sys.stderr)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    text = f"first={json.dumps([first])}\nrest={json.dumps(rest)}\n"
    target = out or os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    return first, rest


def main():
    args = sys.argv[1:]
    if args[:1] == ["updates"] and len(args) == 2:
        plan(json.loads(args[1]), update_entry_size, "Fabric before NeoForge, then an update within one release family, then the oldest game")
    elif args[:1] == ["check"]:
        files = [Path(a) for a in args[1:]] or sorted(WORKFLOWS.glob("*.yml"))
        problems = []
        for path in files:
            wf = load_workflow(path)
            problems += [f"{path.name}: {p}" for p in order_problems(wf) + entry_point_problems(wf)]
        print("\n".join(problems) or f"{len(files)} workflows: every matrix waits on its first entry, every import is there")
        sys.exit(1 if problems else 0)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
