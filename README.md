# The tools behind pull request #222 (one home per job)

Not part of the product: kept on this branch so a branch written before #222 can be brought across it. Delete the
branch when no such branch is left.

`split/Split.java` moves members of a java class to other classes by a plan, re-pointing every use from javac's own
symbols. `split/pysplit.py` does the same for one python module. The plans are the `.plan` files.

## Bringing a branch across #222

#222 landed on main as these commits (in order):

    A  a81ebf5d  porter sources moved to tools/port, lists built from folders   (by hand)
    B  5a6f4697  the java split                                                (tool only)
    R  295be198  scoreboard reads ROOT from port_tools                          (by hand, one line)
    P  4a32cf11  the python split                                              (tool only)
    then two commits by hand (checks, three merged copies)

From your branch, with this branch's `split/` folder copied somewhere outside the repo (`git archive
origin/t3/one-home-per-job-tool split | tar -x -C ~/one-home`):

    git merge a81ebf5d                 # ordinary conflicts. Where you added a porter file to a list, keep main's side
                                       # and `git mv` your file under tools/port/ instead: the folder is the list now.
    bash ~/one-home/split/apply.sh     # re-does the java split on your version of the files
    git add -A && git commit -m "Apply the split"
    git merge -s ours 5a6f4697         # records the split as merged: your tree already is the split of your code
    git merge 295be198
    python ~/one-home/split/pysplit.py ~/one-home/split/exposure.plan
    git add -A && git commit -m "Apply the scoreboard split"
    git merge -s ours 4a32cf11
    git merge origin/main              # the commits by hand, and whatever landed since: ordinary conflicts
    python bench/run_tests.py

A method, field or function you added to a split file stays in the host file (`JarPort.java`, `exposure.py`) unless a
plan names it: add its name to the indented line under the part it belongs to (`name()` for a method). The tool
stops and says so when a name it must move is used from the wrong side.
