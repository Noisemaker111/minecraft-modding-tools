"""cluster.py SURVEY.json HOST PLAN: reads the plan's part/promote lines for HOST as seeds, then gives every member no line names
to the one home all its users are in (a helper follows its only user). Prints the members it would add per home (--write appends
them to the plan's part) and what stays in the host."""
import json, sys, re
survey, host, plan = sys.argv[1], sys.argv[2], sys.argv[3]
write = '--write' in sys.argv
ms = json.load(open(survey))[host]
by = {m['key']: m for m in ms}
users = {}
for m in ms:
    for u in m['uses']:
        users.setdefault(u, set()).add(m['key'])
home = {}
lines = open(plan, encoding='utf-8').read().split('\n')
cur = None; inhost = False; part_line = {}
for i, l in enumerate(lines):
    w = l.split()
    if not w or w[0].startswith('#'): continue
    if l[0] in ' \t' and cur and inhost:
        for k in w: home[k] = cur
        part_line[cur] = i
        continue
    if w[0] == 'host': inhost = w[1] == host; cur = None
    elif w[0] == 'part' and inhost: cur = w[1]; part_line[cur] = i
    elif w[0] == 'promote' and inhost: home[w[1]] = 'promote:' + w[1]; cur = None
    else: cur = None
unknown = [k for k in home if k not in by]
if unknown: print('plan names members the host lacks:', ' '.join(unknown))
added = {}
changed = True
while changed:
    changed = False
    for m in ms:
        k = m['key']
        if k in home or k == '<init>()' or k == '<init-block>': continue
        us = users.get(k, set()) - {k}
        if not us: continue
        hs = {home.get(u, 'CORE') for u in us}
        if len(hs) == 1:
            h = next(iter(hs))
            if h == 'CORE': continue
            if h.startswith('promote:'):
                continue
            home[k] = h; added.setdefault(h, []).append(k); changed = True
for h, ks in added.items():
    print(f'+ {h}: {" ".join(ks)}')
core = [m for m in ms if m['key'] not in home]
print(f'stays in {host}: {len(core)} members, {sum(m["lines"] for m in core)} lines')
for m in sorted(core, key=lambda m: -m['lines'])[:int(next((a.split("=")[1] for a in sys.argv if a.startswith("--top=")), 40))]:
    us = users.get(m['key'], set())
    hs = sorted({home.get(u, 'CORE') for u in us})
    print(f"   {m['lines']:5d} {m['key']:28s} used by {', '.join(hs)[:110]}")
sizes = {}
for m in ms:
    h = home.get(m['key'], 'CORE'); sizes[h] = sizes.get(h, 0) + m['lines']
print('sizes:', ', '.join(f'{h} {n}' for h, n in sorted(sizes.items(), key=lambda x: -x[1])))
if write and added:
    for h, ks in sorted(added.items(), key=lambda x: -part_line[x[0]]):
        lines.insert(part_line[h] + 1, '  ' + ' '.join(ks))
    open(plan, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
