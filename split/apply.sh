#!/bin/bash
# The Java split, from a tree at the "tools/port" layout: three passes of Split.java. Run from the repo root.
# J25 is the bin folder of a JDK 25 (mmt keeps one in ~/.mmt/jdks).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
J25="${J25:-$(ls -d "$HOME"/.mmt/jdks/temurin-25-*/bin 2>/dev/null | head -1)}"
"$J25/java" "$HERE/Split.java" "$HERE/jarport.plan"
"$J25/java" "$HERE/Split.java" "$HERE/pass2.plan"
"$J25/java" "$HERE/Split.java" "$HERE/cli.plan"
