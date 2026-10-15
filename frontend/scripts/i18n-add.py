"""Appends translations to EN in src/i18n.en.ts. Usage: python scripts/i18n-add.py file.tsv (lines: french<TAB>english)."""
import sys

path = 'src/i18n.en.ts'
src = open(path, encoding='utf8').read()
marker = '\n}\n\n/** English messages for API error codes'
assert marker in src
lines = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf8') if l.strip()]

def js(s):
    return "'" + s.replace('\\', '\\\\').replace("'", "\\'") + "'"

added = ''.join(f'\n  {js(fr)}: {js(en)},' for fr, en in lines if f'{js(fr)}:' not in src)
src = src.replace(marker, added + marker, 1)
open(path, 'w', encoding='utf8').write(src)
print(f'{added.count(chr(10))} translations added')
