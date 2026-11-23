import json, sys
d = json.load(open(sys.argv[1]))
ms = d[sys.argv[2]]
users = {}
for m in ms:
    for u in m['uses']: users.setdefault(u, []).append(m['key'])
row = []
for m in ms:
    k = m['key']; us = users.get(k, [])
    tag = ('S' if m['static'] else '') + m['kind'][0] + ('O' if m.get('outer') else '')
    row.append(f"{m['line']}:{m['lines']}{tag} {k}<{','.join(u for u in us[:2])}{'+%d' % (len(us)-2) if len(us) > 2 else ''}>")
print(' | '.join(row))
