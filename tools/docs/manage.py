# SPDX-License-Identifier: GPL-3.0-or-later
"""Documentation index and preservation checks. Python 3.10+, standard library only."""
from pathlib import Path
import argparse,hashlib,json,os,posixpath,re,sys
from urllib.parse import quote,unquote,urlsplit
sys.dont_write_bytecode=True
import documentation
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
import android_version
META=ROOT/'docs/_meta'
GENERATED={'docs/README.md','docs/STATUS.md','docs/VERSIONS.md','docs/FILE_INDEX.md','docs/_meta/catalog.json'}
SKIP={'.git','.gradle','.idea','.kotlin','.venv','__pycache__','build','node_modules','.documentation-backups'}

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def dump(obj):return json.dumps(obj,indent=2,ensure_ascii=False)+'\n'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def safe(rel):return documentation.inside(ROOT,rel)
def write(rel,text):
 p=safe(rel);data=(text.rstrip()+'\n').encode('utf-8')
 if p.is_file() and p.read_bytes()==data:return
 p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
def configs():return read(META/'inventory.json'),read(META/'versions.json')['versions'],read(META/'status.json')
def files():
 for base,dirs,names in os.walk(ROOT,followlinks=False):
  dirs[:]=sorted(d for d in dirs if d not in SKIP)
  for d in dirs:
   p=Path(base)/d
   if p.is_symlink() or (hasattr(p,'is_junction') and p.is_junction()):raise ValueError('Unexpected linked directory: '+str(p))
  for name in sorted(names):
   p=Path(base)/name;rel=p.relative_to(ROOT).as_posix()
   if rel in GENERATED or rel.startswith('app/src/main/assets/licenses/'):continue
   if p.suffix.lower() in {'.md','.mdc','.rst','.adoc'} or rel.startswith(('tools/docs/','docs/_meta/')):
    if p.is_symlink():raise ValueError('Unexpected linked file: '+str(p))
    yield p
def local_links(rel,body):
 # Ignore fenced examples so sample Markdown is not treated as a live link.
 body=re.sub(r'(?ms)^```.*?^```[^\n]*','',body)
 for href in re.findall(r'\]\(([^)]+)\)',body):
  u=urlsplit(href)
  if u.scheme or href.startswith(('#','/')) or '<' in href:continue
  target=posixpath.normpath(posixpath.join(posixpath.dirname(rel),unquote(u.path)))
  if target=='..' or target.startswith('../'):yield href,None
  else:yield href,target
def link(label,rel,source='docs/README.md'):
 return '['+label.replace('|','\\|')+']('+quote(posixpath.relpath(rel,posixpath.dirname(source)),safe='/.-_')+')'

def collect():
 inventory,versions,status=configs();originals={e['path']:e for e in inventory['originals']}
 old=read(META/'catalog.json') if (META/'catalog.json').exists() else {};previous={e['path']:e for e in old.get('files',[])}
 entries=[];errors=[]
 for p in files():
  rel=p.relative_to(ROOT).as_posix()
  e=dict(path=rel,bytes=p.stat().st_size,sha256=sha(p),version='current-source',category='documentation',kind='guide',frozen=False,note='Maintained documentation/tooling')
  if rel in originals:
   info=originals[rel];e.update({k:v for k,v in info.items() if k!='sha256'})
   if info['frozen'] and info['sha256']!=e['sha256']:errors.append('Original record changed: '+rel)
  elif rel.startswith('docs/history/'):
   e.update(version='unassigned',category='unassigned',kind='record')
  elif rel.startswith(('docs/maintainers/','tools/docs/','docs/_meta/','.cursor/')) or rel=='AGENTS.md':e['category']='governance'
  elif rel.startswith('docs/guides/'):e['category']='usage'
  elif rel.startswith('docs/integrations/'):e['category']='authentication'
  elif rel.startswith('docs/diagnostics/'):e['category']='diagnostics'
  elif rel.startswith('docs/reference/'):e['category']='reference'
  else:errors.append('Unclassified new documentation; place it under docs/ or register a pinned exception: '+rel)
  entries.append(e)
 present={e['path'] for e in entries}
 for rel,e in originals.items():
  if not safe(rel).is_file():errors.append('Missing inventoried documentation: '+rel)
  elif e['kind']=='evidence' and rel not in present:
   entries.append({**e,'sha256':sha(safe(rel)),'bytes':safe(rel).stat().st_size})
 errors+=documentation.audit(ROOT,entries,previous,versions,set(originals),GENERATED)
 errors+=['Unassigned record: '+e['path'] for e in entries if e['version']=='unassigned']
 # New root Markdown must be explicitly pinned rather than accumulating again.
 errors+=['Unexpected root documentation: '+p.name for p in ROOT.glob('*.md') if p.name not in {'README.md','AGENTS.md','CHANGELOG.md','PRIVACY_POLICY.md','TERMS_AND_CONDITIONS.md','THIRD_PARTY_NOTICES.md'}]
 versions_meta=read(META/'versions.json');active=next((v for v in versions if v['id']==versions_meta['current']),None)
 try:
  name,code=android_version.resolve_version(android_version.VERSION_FILE.read_text(encoding='utf-8').rstrip('\r\n'))
 except (OSError,ValueError) as exc:
  errors.append('Invalid configured Android version: '+str(exc))
 else:
  if not active or active['versionCode']!=code or active['versionName']!=name:errors.append('Registry current version differs from the configured Android release identity')
 baseline=read(META/'legacy-links.json') if (META/'legacy-links.json').exists() else []
 known={(x['path'],x['href']) for x in baseline};broken=[]
 for e in entries:
  if not e['path'].endswith('.md') or e['frozen']:continue
  for href,target in local_links(e['path'],safe(e['path']).read_text(encoding='utf-8-sig')):
   if target is None or (target not in GENERATED and not (ROOT/target).exists()):
    broken.append(dict(path=e['path'],href=href))
    if (e['path'],href) not in known:errors.append('Broken new local link: '+e['path']+' -> '+href)
 entries.sort(key=lambda x:x['path'].casefold())
 return inventory,versions,status,entries,errors,broken

def render(inventory,versions,status,entries,broken):
 fingerprint=hashlib.sha256(dump(entries).encode()).hexdigest()
 cat=dict(schemaVersion=1,fingerprint=fingerprint,files=entries,generated=sorted(GENERATED),legacyBrokenLinks=broken,
          oldPaths={e['source']:e['destination'] for e in inventory['moves']})
 intro='Generated navigation; edit the relevant sources, then run tools/docs/manage.py refresh. Indexing does not establish a new factual review.\n\n'
 docs={'docs/README.md':'# Documentation directory\n\n'+intro+'''Start with [root README](../README.md), [agent instructions](../AGENTS.md), [current status](STATUS.md), [authoring](maintainers/AUTHORING.md) and [file index](FILE_INDEX.md).

Current guides are grouped by usage, reference, integrations and diagnostics. Reusable workflow is under maintainers/. Task history is grouped by version and category. Module READMEs and QA evidence retain their contextual locations and are indexed below. Old instructions are historical, not executable authorization.

Use `python tools/docs/manage.py locate NAME` for former root/guidelines/historical_prompt paths. Do not recreate root redirects.

''', 'docs/STATUS.md':'# Current status\n\n'+intro+f"Facts reviewed: {status['reviewedOn']} ({status['timeZone']}).\n\n",
 'docs/VERSIONS.md':'# Documentation version map\n\n'+intro+'Codes below are attributed configuration/historical evidence; null remains unknown. A grouping is not proof that every file describes that exact APK.\n\n| Identity | Code | Authorable | Evidence |\n|---|---|---|---|\n',
 'docs/FILE_INDEX.md':'# Documentation file index\n\n'+intro+f'{len(entries)} documentation and management sources. Legacy original paths/hashes and known conflicts are retained in the machine-readable catalog/inventory.\n\n| File | Version/group | Category | Kind |\n|---|---|---|---|\n'}
 for fact in status['facts']:docs['docs/STATUS.md']+='## '+fact['subject']+'\n\n'+fact['value']+'\n\n'
 docs['docs/STATUS.md']+='## Next steps\n\n'+''.join(f'{i}. {t}\n' for i,t in enumerate(status['nextSteps'],1))
 for v in versions:
  docs['docs/VERSIONS.md']+=f"| {v['id']} | {v['versionCode'] if v['versionCode'] is not None else 'unknown / n/a'} | {v['authorable']} | {v['evidence']} |\n"
 for category in sorted({e['category'] for e in entries}):
  docs['docs/README.md']+='## '+category+'\n\n'
  # One navigation entry per maintained guide; full history is in FILE_INDEX/catalog.
  for e in entries:
   if e['category']==category and not e['frozen'] and e['path'].endswith('.md'):
    docs['docs/README.md']+='- '+link(e['path'],e['path'])+'\n'
  docs['docs/README.md']+='\n'
 for e in entries:docs['docs/FILE_INDEX.md']+=f"| {link(e['path'],e['path'])} | {e['version']} | {e['category']} | {e['kind']} |\n"
 docs['docs/FILE_INDEX.md']+='\n## Preserved conflicts and context\n\n'+''.join('- '+link(e['path'],e['path'])+': '+e['note']+'\n' for e in entries if 'Conflict:' in e['note'] or 're-scoped' in e['note'])
 docs['docs/FILE_INDEX.md']+=f'\nKnown pre-existing local-link issues: {len(broken)}. See docs/_meta/legacy-links.json; these are not silently treated as newly verified links.\n'
 docs['docs/_meta/catalog.json']=dump(cat)
 return {rel: text.rstrip()+'\n' for rel,text in docs.items()}

def refresh(check=False):
 inventory,versions,status,entries,errors,broken=collect()
 if errors:print('\n'.join(errors),file=sys.stderr);return 1
 outputs=render(inventory,versions,status,entries,broken)
 for rel,text in outputs.items():
  if check:
   if not safe(rel).exists() or safe(rel).read_bytes()!=text.encode('utf-8'):errors.append('Stale index: '+rel)
  else:write(rel,text)
 for rel,body in outputs.items():
  if not rel.endswith('.md'):continue
  for href,target in local_links(rel,body):
   if target is None or not (ROOT/target).exists():errors.append('Broken generated link: '+rel+' -> '+href)
 if errors:print('\n'.join(errors),file=sys.stderr);return 1
 print(f"{'Checked' if check else 'Refreshed'} {len(entries)} documentation sources; preserved originals and structured records; current code/name match; {len(broken)} legacy link exceptions.")
 return 0

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['refresh','check','locate']);parser.add_argument('name',nargs='?');args=parser.parse_args()
 if args.command=='locate':
  if not args.name:parser.error('locate requires an old path or filename')
  q=args.name.replace('\\','/').casefold();cat=read(META/'catalog.json')
  if q.startswith(ROOT.as_posix().casefold()+'/'):q=q[len(ROOT.as_posix())+1:]
  hits=[e for e in cat['files'] if q in e['path'].casefold() or q in e.get('originalPath','').casefold()]
  for e in hits:print(str(ROOT/e['path']))
  return 0 if hits else 1
 return refresh(args.command=='check')

if __name__=='__main__':
 try:sys.exit(main())
 except (ValueError,OSError,KeyError) as exc:print('Error: '+str(exc),file=sys.stderr);sys.exit(1)
