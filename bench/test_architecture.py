"""The porter's sources are listed in one place: no other code names a file under tools/port.

    python bench/test_architecture.py    (run by bench/run_tests.py)

A file under tools/port is compiled, shipped and hashed by being there: bench/port_tools.py lists the folder for the
checks and the scoreboard, tools/cli/JavaTools.java (portSources) for mmt. Nine hand-kept copies of that list came
before; each new porter file had to be added to all of them, every pull request collided in them, and one that missed
a copy compiled in the checks and failed in the shipped jar. A second list starts the same way: code that names one
porter file. This fails on it and says what to call instead. ARCHITECTURE.md has the whole layout.

Text that only talks about a file (a comment, a docstring, a message) is not a list and is not read; tests may name
the files they build fixtures of.
"""
import ast
import re
import sys
from pathlib import Path

import port_tools

ROOT = Path(__file__).resolve().parent.parent
NAMED = re.compile(r"(?:tools/)?port/(?:\w+/)*\w+\.java")
HOMES = {"bench/port_tools.py", "tools/cli/JavaTools.java"}
JAVA_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def python_strings(path):
    """Every string a python file computes with: not its docstrings."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    docs = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.body and isinstance(node.body[0], ast.Expr) \
                and isinstance(node.body[0].value, ast.Constant):
            docs.add(id(node.body[0].value))
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs:
            yield node.lineno, node.value


def java_strings(path):
    """Every string literal of a java file outside its comments; a string that reads as a sentence is a message."""
    block = False
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        code = line.strip()
        if block or code.startswith(("/*", "*", "//")):
            block = (block or code.startswith("/*")) and "*/" not in code
            continue
        for m in JAVA_STRING.finditer(line.split("//")[0]):
            if " " not in m.group(1).strip():
                yield n, m.group(1)


def second_lists(root=ROOT):
    found = []
    code = [(p, python_strings) for p in sorted((root / "bench").rglob("*.py")) if not p.name.startswith("test_")]
    code += [(p, python_strings) for p in sorted((root / "tools").glob("*.py"))]
    code += [(p, java_strings) for p in sorted((root / "tools" / "cli").rglob("*.java"))]
    for path, strings in code:
        name = path.relative_to(root).as_posix()
        if name in HOMES or "__pycache__" in name:
            continue
        for line, text in strings(path):
            if NAMED.search(text) and " " not in text.strip():
                found.append(f"{name}:{line} names the porter source {NAMED.search(text).group(0)}. The porter's sources are every file under tools/port, listed "
                             f"once: call port_tools.sources() (python) or JavaTools.portSources (java), so a new file needs no list changed (ARCHITECTURE.md)")
    return found


def catches():
    """The check on a made-up tree: it must find a list in code, and not one in a comment, a docstring or a message."""
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "bench").mkdir()
        (root / "tools" / "cli").mkdir(parents=True)
        (root / "bench" / "sweep.py").write_text('"""Reads tools/port/jar/JarPort.java."""\nSOURCES = ["tools/port/jar/JarPort.java", "x"]\n', encoding="utf-8")
        (root / "bench" / "notes.py").write_text('"""Compiles tools/port/learn/GameDiff.java, as port_tools lists it."""\nX = 1\n', encoding="utf-8")
        (root / "tools" / "cli" / "Lister.java").write_text('// tools/port/names/VersionMap.java is read here\nclass Lister {\n    String a = "port/names/VersionMap.java";\n'
                                                            '    String b = "Compile tools/port/names/VersionMap.java first.";\n}\n', encoding="utf-8")
        found = second_lists(root)
    return [] if len(found) == 2 and found[0].startswith("bench/sweep.py:2 ") and found[1].startswith("tools/cli/Lister.java:3 ") else [
        f"the check itself is wrong: on a tree with one list in python and one in java it reported {found}"]


def main():
    found = catches() + second_lists()
    listed = set(port_tools.sources())
    for path in sorted((ROOT / port_tools.PORT).rglob("*.java")):
        if path.relative_to(ROOT).as_posix() not in listed:
            found.append(f"{path.relative_to(ROOT).as_posix()} is under tools/port and port_tools.sources() leaves it out: it lists the folder, so nothing should be")
    print("\n".join(found) or f"ok: {len(listed)} porter sources, listed in one place")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
