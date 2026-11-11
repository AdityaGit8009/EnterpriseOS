"""Check literal Spring MVC routes against the merged published contract."""
import json,re
from pathlib import Path
root=Path(__file__).resolve().parent.parent
spec=json.loads((root/'docs/api/openapi.json').read_text(encoding='utf-8-sig'))
missing=[];count=0
for file in (root/'services/api/src/main/java/com/enterpriseos').glob('*Controller.java'):
    source=file.read_text(encoding='utf-8-sig')
    prefix=source.split('public class',1)[0]
    base=re.search(r'@RequestMapping\("([^"]+)"\)',prefix)
    base=base.group(1) if base else ''
    for match in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?',source):
        value=match.group(2) or ''
        paths=re.findall(r'"([^"\n]*)"',value)
        # These controllers use string paths, or value/path plus produces.
        paths=paths[:1] if 'produces' in value or 'consumes' in value else paths
        for path in paths or ['']:
            route=base+path;method=match.group(1).lower()
            if not route.startswith('/api/v1'): continue
            count+=1
            if method not in spec['paths'].get(route,{}):missing.append(f'{method.upper()} {route} ({file.name})')
if missing:raise SystemExit('Undocumented literal routes:\n'+'\n'.join(missing))
print(f'{count} literal controller mappings have published OpenAPI operations.')
