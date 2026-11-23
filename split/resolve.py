"""resolve.py FILE CHOICE...: resolves the conflict hunks of FILE in order; each CHOICE is ours, theirs, both, or @TEXTFILE (replacement text)."""
import re, sys
p = sys.argv[1]
raw = open(p, encoding='utf-8', newline='').read(); nl = '\r\n' if '\r\n' in raw else '\n'
s = raw.replace('\r\n', '\n')
pat = re.compile(r'<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n', re.S)
choices = sys.argv[2:]
i = [0]
def pick(m):
    c = choices[i[0]]; i[0] += 1
    if c == 'ours': return m.group(1)
    if c == 'theirs': return m.group(2)
    if c == 'both': return m.group(1) + m.group(2)
    if c == 'theirs-ours': return m.group(2) + m.group(1)
    return open(c[1:], encoding='utf-8').read().replace('\r\n', '\n')
out, n = pat.subn(pick, s)
assert n == len(choices), (n, len(choices))
open(p, 'w', encoding='utf-8', newline='').write(out.replace('\n', nl))
