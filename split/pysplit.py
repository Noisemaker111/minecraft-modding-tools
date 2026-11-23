"""pysplit.py PLAN [--dry] [--survey]: move top-level definitions of one python module into the modules of a package.

Moves only: a statement's text goes to its new module unchanged, but for the module name in front of a name that now
lives elsewhere (maps.route_map). Cross-module names are always read as attributes at call time, never from-imported,
so a test that replaces portboard.maps.fetch still replaces it for every caller.

Plan lines (# comments):
    file bench/exposure.py              the module to split (stays as the entry point)
    package bench/portboard             where the new modules go (importable as portboard from the file's folder)
    user bench/test_exposure.py E       a file that imports the module, and the name it imports it as
    module NAME | One sentence.         a new module
      name name ...                     (indented) the top-level names that move there
A name nothing lists stays in the file.
"""
import ast
import builtins
import sys
from pathlib import Path


def lines_of(text):
    starts = [0]
    for i, c in enumerate(text):
        if c == "\n":
            starts.append(i + 1)
    return starts


class Source:
    def __init__(self, path):
        self.path = Path(path)
        raw = self.path.read_bytes().decode("utf-8")
        self.crlf = "\r\n" in raw
        self.text = raw.replace("\r\n", "\n")
        self.tree = ast.parse(self.text)
        self.starts = lines_of(self.text)
        self.bytes_lines = [line.encode("utf-8") for line in self.text.split("\n")]
        self.edits = []

    def offset(self, lineno, col):
        # ast columns count utf-8 bytes
        return self.starts[lineno - 1] + len(self.bytes_lines[lineno - 1][:col].decode("utf-8"))

    def span(self, node):
        return self.offset(node.lineno, node.col_offset), self.offset(node.end_lineno, node.end_col_offset)

    def edit(self, start, end, text):
        self.edits.append((start, end, text))

    def apply(self, start, end):
        out, at = [], start
        for s, e, t in sorted(x for x in self.edits if x[0] >= start and x[1] <= end):
            if s < at:
                raise SystemExit(f"overlapping edits in {self.path} near {self.text.count(chr(10), 0, s) + 1}")
            out.append(self.text[at:s])
            out.append(t)
            at = e
        out.append(self.text[at:end])
        return "".join(out)

    def write(self, path, text):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        while "\n\n\n\n" in text:
            text = text.replace("\n\n\n\n", "\n\n\n")
        path.write_bytes((text.replace("\n", "\r\n") if self.crlf else text).encode("utf-8"))


def bound(stmt):
    """The names a top-level statement defines."""
    if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        return [stmt.name]
    if isinstance(stmt, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
        targets = stmt.targets if isinstance(stmt, ast.Assign) else [stmt.target]
        names = []
        for t in targets:
            for n in ast.walk(t):
                if isinstance(n, ast.Name):
                    names.append(n.id)
        return names
    return []


def locals_of(fn):
    """Names local to a function or lambda: its parameters and whatever its own body binds."""
    names, declared = set(), set()
    args = fn.args
    for a in args.posonlyargs + args.args + args.kwonlyargs + ([args.vararg] if args.vararg else []) + ([args.kwarg] if args.kwarg else []):
        names.add(a.arg)
    body = fn.body if isinstance(fn.body, list) else [fn.body]

    def visit(node):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
            return
        if isinstance(node, ast.Lambda):
            return
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            # A comprehension's variables are its own; what it reads is this function's business.
            for child in ast.iter_child_nodes(node):
                if not isinstance(child, ast.comprehension):
                    visit_reads_only(child)
            for gen in node.generators:
                visit_reads_only(gen.iter)
            return
        if isinstance(node, ast.Global):
            declared.update(node.names)
        if isinstance(node, ast.Nonlocal):
            declared.update(node.names)
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            names.add(node.id)
        if isinstance(node, ast.ExceptHandler) and node.name:
            names.add(node.name)
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            for alias in node.names:
                names.add((alias.asname or alias.name).split(".")[0])
        if isinstance(node, (ast.MatchAs, ast.MatchStar)) and node.name:
            names.add(node.name)
        if isinstance(node, ast.MatchMapping) and node.rest:
            names.add(node.rest)
        for child in ast.iter_child_nodes(node):
            visit(child)

    def visit_reads_only(node):
        # walrus inside a comprehension binds in the enclosing function
        for n in ast.walk(node):
            if isinstance(n, ast.NamedExpr) and isinstance(n.target, ast.Name):
                names.add(n.target.id)

    for stmt in body:
        visit(stmt)
    return names - declared, declared


def comp_locals(node):
    names = set()
    for gen in node.generators:
        for n in ast.walk(gen.target):
            if isinstance(n, ast.Name):
                names.add(n.id)
    return names


def main():
    plan_path = sys.argv[1]
    dry = "--dry" in sys.argv
    target = package = None
    users, modules, home, docs = [], [], {}, {}
    current = None
    for raw in Path(plan_path).read_text(encoding="utf-8").split("\n"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        w = line.split()
        if raw[0] in " \t" and current:
            for name in w:
                if name in home:
                    raise SystemExit(f"plan names {name} twice")
                home[name] = current
            continue
        if w[0] == "file":
            target = Source(w[1])
        elif w[0] == "package":
            package = Path(w[1])
        elif w[0] == "user":
            users.append((Source(w[1]), w[2]))
        elif w[0] == "module":
            current = w[1]
            modules.append(current)
            docs[current] = line.split("|", 1)[1].strip() if "|" in line else ""
        else:
            raise SystemExit(f"plan: {line}")
    pkg = package.name
    src = target
    body = src.tree.body
    defined = {}
    stmt_home, stmt_range = {}, {}
    cursor = 0
    header_imports = []
    problems = []
    for i, stmt in enumerate(body):
        end = src.starts[stmt.end_lineno] if stmt.end_lineno < len(src.starts) else len(src.text)
        stmt_range[i] = (cursor, end)
        cursor = end
        names = bound(stmt)
        homes = {home.get(n, "") for n in names}
        if len(homes) > 1:
            problems.append(f"line {stmt.lineno}: one statement defines names that go to different modules: {names}")
        h = homes.pop() if homes else ""
        stmt_home[i] = h
        for n in names:
            if n in defined and defined[n] != h:
                problems.append(f"{n} is defined twice, in different modules")
            defined[n] = h
        if isinstance(stmt, (ast.Import, ast.ImportFrom)):
            header_imports.append(i)
    for n in home:
        if n not in defined:
            print(f"note: no top-level name {n}", file=sys.stderr)
    if "--survey" in sys.argv:
        for i, stmt in enumerate(body):
            for n in bound(stmt):
                print(f"{stmt.lineno}:{stmt.end_lineno - stmt.lineno + 1} {n} -> {stmt_home[i] or '(stays)'}")
        return

    uses = {m: set() for m in modules + [""]}       # module -> sibling modules it names
    hard = {m: set() for m in modules + [""]}       # module -> sibling modules it reads while being imported
    names_used = {m: set() for m in modules + [""]}  # module -> every bare name its statements read (for the import lines)

    def rewrite(node, site, scopes, depth, declared):
        """scopes: list of sets of local names, innermost last. depth: 0 at import time, >0 inside a function body."""
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for d in node.decorator_list:
                rewrite(d, site, scopes, depth, declared)
            for default in node.args.defaults + [d for d in node.args.kw_defaults if d is not None]:
                rewrite(default, site, scopes, depth, declared)
            for a in node.args.posonlyargs + node.args.args + node.args.kwonlyargs + ([node.args.vararg] if node.args.vararg else []) + ([node.args.kwarg] if node.args.kwarg else []):
                if a.annotation:
                    rewrite(a.annotation, site, scopes, depth, declared)
            if node.returns:
                rewrite(node.returns, site, scopes, depth, declared)
            local, dec = locals_of(node)
            for alias in set(modules) & local:
                if any(isinstance(n, ast.Name) and n.id in defined and defined[n.id] == alias for n in ast.walk(node)):
                    pass
            for stmt in node.body:
                rewrite(stmt, site, scopes + [local], depth + 1, dec)
            return
        if isinstance(node, ast.Lambda):
            for default in node.args.defaults + [d for d in node.args.kw_defaults if d is not None]:
                rewrite(default, site, scopes, depth, declared)
            local, _ = locals_of(node)
            rewrite(node.body, site, scopes + [local], depth + 1, declared)
            return
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            inner = scopes + [comp_locals(node)]
            for child in ast.iter_child_nodes(node):
                rewrite(child, site, inner, depth, declared)
            return
        if isinstance(node, ast.ClassDef):
            for d in node.decorator_list + node.bases:
                rewrite(d, site, scopes, depth, declared)
            class_names = set()
            for stmt in node.body:
                class_names.update(bound(stmt))
            clash = class_names & set(defined)
            if clash:
                problems.append(f"line {node.lineno}: class {node.name} binds {sorted(clash)}, also module-level names")
            for stmt in node.body:
                rewrite(stmt, site, scopes, depth, declared)
            return
        if isinstance(node, ast.Global):
            moved = [n for n in node.names if n in defined and defined[n] != site]
            if moved:
                kept = [n for n in node.names if n not in moved]
                start, end = src_now.span(node)
                if kept:
                    src_now.edit(start, end, "global " + ", ".join(kept))
                else:
                    # the whole line goes
                    line_start = src_now.starts[node.lineno - 1]
                    line_end = src_now.starts[node.end_lineno] if node.end_lineno < len(src_now.starts) else len(src_now.text)
                    src_now.edit(line_start, line_end, "")
            return
        if isinstance(node, ast.Name):
            name = node.id
            if any(name in s for s in scopes):
                if name in modules and False:
                    pass
                return
            names_used[site].add(name)
            if name in defined:
                to = defined[name]
                if to != site:
                    if to == "":
                        problems.append(f"line {node.lineno}: {name} stays in the entry file but {site} uses it; move it or its user")
                        return
                    start, _ = src_now.span(node)
                    src_now.edit(start, start, to + ".")
                    uses[site].add(to)
                    if depth == 0:
                        hard[site].add(to)
                    for s in scopes:
                        if to in s:
                            problems.append(f"line {node.lineno}: a local named {to} hides the module {to}")
            return
        for child in ast.iter_child_nodes(node):
            rewrite(child, site, scopes, depth, declared)

    global src_now
    src_now = src
    for i, stmt in enumerate(body):
        if i in header_imports:
            continue
        for n in ast.walk(stmt):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "globals":
                print(f"note: line {n.lineno} reads names through globals(): what it names must live in its module", file=sys.stderr)
            if isinstance(n, ast.Name) and n.id == "__file__" and stmt_home[i] != "":
                problems.append(f"line {n.lineno}: __file__ in a statement that moves")
        rewrite(stmt, stmt_home[i], [], 0, set())

    # Users of the module: E.name where name moved becomes portboard.MODULE.name.
    user_needs = {}
    for user, alias in users:
        needs = set()
        for node in ast.walk(user.tree):
            if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == alias and defined.get(node.attr, ""):
                to = defined[node.attr]
                start, end = user.span(node.value)
                user.edit(start, end, f"{pkg}.{to}")
                needs.add(to)
            # setattr(E, "name", ...) names it in a string
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ("setattr", "getattr", "hasattr", "delattr") and len(node.args) >= 2
                    and isinstance(node.args[0], ast.Name) and node.args[0].id == alias and isinstance(node.args[1], ast.Constant) and defined.get(node.args[1].value, "")):
                to = defined[node.args[1].value]
                start, end = user.span(node.args[0])
                user.edit(start, end, f"{pkg}.{to}")
                needs.add(to)
        user_needs[user.path] = needs
        # the import line for them goes after the line that imports the module, wherever that is
        for node in ast.walk(user.tree):
            hit = isinstance(node, ast.Import) and any((a.asname or a.name) == alias for a in node.names)
            if hit and needs:
                end = user.starts[node.end_lineno] if node.end_lineno < len(user.starts) else len(user.text)
                tail = user.text[user.span(node)[1]:end].rstrip("\n")
                noqa = "  # noqa: E402" if "noqa" in tail else ""
                indent = " " * node.col_offset
                user.edit(end, end, "".join(f"{indent}import {pkg}.{m}{noqa}\n" for m in sorted(needs)))
                break
        else:
            if needs:
                problems.append(f"{user.path}: no import of the module as {alias}")

    # A module read while another is being imported must not import its siblings back.
    for m, targets in hard.items():
        for t in targets:
            if uses[t]:
                problems.append(f"{m or 'the entry file'} reads {t} at import time, but {t} imports {sorted(uses[t])}: keep what is read at import time in a module that imports no sibling")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        raise SystemExit(2)
    if dry:
        for m in modules:
            print(m, "uses", sorted(uses[m]), "hard", sorted(hard[m]))
        return

    def import_lines(module):
        out = []
        used = names_used[module]
        for i in header_imports:
            stmt = body[i]
            binds = {(a.asname or a.name).split(".")[0] for a in stmt.names}
            if binds & used:
                s, e = stmt_range[i]
                out.append(src.text[src.span(stmt)[0]:e])
        return "".join(out)

    for m in modules:
        parts = [src.apply(*stmt_range[i]) for i in range(len(body)) if stmt_home[i] == m]
        if not parts:
            continue
        text = f'"""{docs[m]}"""\n' + import_lines(m)
        if uses[m]:
            text += f"\nfrom {pkg} import {', '.join(sorted(uses[m]))}\n"
        text += "".join(parts)
        text = text.rstrip("\n") + "\n"
        src.write(package / f"{m}.py", text)
    # what stays: every statement not moved, with the package's modules imported after the file's own imports
    kept = []
    last_import = max(header_imports) if header_imports else -1
    # a statement right after the imports that is itself part of the import block (sys.path.insert, a late import) stays above ours
    for i in range(len(body)):
        if stmt_home[i] != "" and i not in header_imports:
            continue
        kept.append(src.apply(*stmt_range[i]))
        if i == last_import and uses[""]:
            kept.append(f"from {pkg} import {', '.join(sorted(uses['']))}  # noqa: E402\n")
    src.write(src.path, "".join(kept))
    for user, alias in users:
        if user.edits:
            user.write(user.path, user.apply(0, len(user.text)))


src_now = None
if __name__ == "__main__":
    main()
