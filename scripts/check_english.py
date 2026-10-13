"""Check maintained source text, never private inputs."""
from pathlib import Path
import re,subprocess,sys
root=Path(__file__).resolve().parents[1]
names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=root,text=True).splitlines()
bad=[]
for name in names:
    p=root/name
    if p.suffix.lower() not in {'.md','.html','.js','.mjs','.css','.json','.py','.yml','.yaml','.xml','.kt','.swift'} or not p.is_file():continue
    for n,line in enumerate(p.read_text(encoding='utf-8-sig').splitlines(),1):
        if re.search(r'[\u3400-\u9fff\uf900-\ufaff]',line):bad.append(f'{name}:{n}')
print('\n'.join(bad) if bad else 'English-only source check passed.')
sys.exit(bool(bad))
