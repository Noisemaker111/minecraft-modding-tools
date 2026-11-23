**Step and finish line:** the roadmap step (`coverage/roadmap/goal-N/NNN-ID.json`), its number today, and the number that ends
this PR.

**Approach:** the method and why it finishes the whole step, not only the next case. What result would prove it
wrong.

**What works now that didn't:** one sentence. Before: what the user saw. After: what they see now.

**Proof:** CI on this PR (porting: its Port check, with a `Port check: LOADER:FROM:TO [mods=SLUG,...] [type=TEXT]`
line here naming the ports), plus the in-game run that proved it (command and receipt). "Not tested in-game" or "saved for the cloud" is not a result.

**Carries over:** what the next mod, of any kind, gets for free from this work: a general perf pattern, a tool or
generator, a faster loop, or a workflow update. A win that only helps the mod it was made on is half a result.

**Not covered:** what this doesn't handle or wasn't checked.

<!-- One roadmap step, one PR: push every commit of the step here and merge at its finish line with every check green. Keep the latest state only, on one screen; rewrite sections instead of adding dated updates. -->
