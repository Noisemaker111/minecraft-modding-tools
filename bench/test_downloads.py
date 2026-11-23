"""The cloud jobs' kept downloads (.github/actions/downloads/downloads.cjs) restore the published files a job used last
time and keep only published files: a release list (Fabric's maven-metadata.xml, Mojang's version list) kept forever
would hide every build published after it. Run against a stand-in for the Actions cache that stores each entry as a
file, two jobs one after the other on a fresh home each, as two cloud runs are.
Run: python bench/test_downloads.py (needs node)"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / ".github" / "actions" / "downloads" / "downloads.cjs"

# An Actions cache whose entries are files in STORE: restoreCache copies an entry back, saveCache refuses a key it has.
JOB = """
const fs = require('fs'), path = require('path');
const store = process.env.STORE;
const cache = {
  restoreCache: async (paths, key) => {
    const entry = path.join(store, encodeURIComponent(key));
    if (!fs.existsSync(entry)) return undefined;
    fs.copyFileSync(entry, paths[0]);
    return key;
  },
  saveCache: async (paths, key) => {
    const entry = path.join(store, encodeURIComponent(key));
    if (fs.existsSync(entry)) throw new Error('already exists');
    fs.copyFileSync(paths[0], entry);
    return 1;
  },
};
const notes = [];
const core = { notice: (m) => notes.push(m), info: () => {} };
(async () => {
  await require(process.env.SCRIPT)[process.env.MODE](cache, core);
  console.log(JSON.stringify(notes));
})();
"""

checks = 0


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1


def job(home, store, mode):
    env = dict(os.environ, HOME=str(home), USERPROFILE=str(home), STORE=str(store), SCRIPT=str(SCRIPT), MODE=mode)
    out = subprocess.run(["node", "-e", JOB], env=env, capture_output=True, text=True, check=True).stdout
    return json.loads(out)


if not shutil.which("node"):
    sys.exit("test_downloads needs node, as the cloud jobs' runners have")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    store = root / "store"
    store.mkdir()
    published = {"intermediary-1.18.2-v2.jar": b"inter", "fabric-api-0.116.18+1.21.1.jar": b"api", "client-1.21.1.txt": b"names"}
    lists = {"fabric-api-maven-metadata.xml": b"<versions/>", "version_manifest_v2.json": b"{}", "version-1.21.1.json": b"{}"}

    # The first job: nothing kept yet, so it downloads everything (here, the files appear while it runs).
    first = root / "first"
    job(first, store, "restore")
    mappings = first / ".mmt" / "mappings"
    for name, data in {**published, **lists}.items():
        (mappings / name).write_bytes(data)
    (mappings / "dl123.part").write_bytes(b"half")
    said = job(first, store, "save")
    kept = sorted(p.name for p in store.iterdir())
    check(kept == sorted("download-v1-" + n.replace("+", "%2B") for n in published),
          f"each published file is kept under its own name; release lists and part files are not: {kept}")
    check("3 downloaded by this job (3 newly kept)" in said[0] and "4 release lists" in said[0], f"the save says what the job downloaded: {said}")
    check((first / ".mmt" / "downloads.txt").read_text().split() == sorted(published), "the list of files the job used names only published files")

    # The next run of that job: a fresh home with only the list (the action's own small cache entry) restores the files.
    second = root / "second"
    (second / ".mmt").mkdir(parents=True)
    shutil.copy(first / ".mmt" / "downloads.txt", second / ".mmt" / "downloads.txt")
    said = job(second, store, "restore")
    restored = second / ".mmt" / "mappings"
    check(sorted(p.name for p in restored.iterdir()) == sorted(published) and all((restored / n).read_bytes() == d for n, d in published.items()),
          f"the next run restores every published file the job used, whole: {sorted(p.name for p in restored.iterdir())}")
    check(said == ["kept downloads: 3 of the 3 files this job used last time restored from the cache"], f"the restore says how many: {said}")
    (restored / "fabric-api-maven-metadata.xml").write_bytes(b"<versions>new</versions>")  # downloaded fresh, as it must be
    said = job(second, store, "save")
    check("3 published files restored, 0 downloaded by this job" in said[0], f"a restored file is not downloaded or kept again: {said}")

print(f"downloads: {checks} checks passed")
