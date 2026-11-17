# SPDX-License-Identifier: GPL-3.0-or-later
"""Create a canonical draft or revision; never overwrites a document."""
import argparse
from pathlib import Path
import subprocess
import sys
sys.dont_write_bytecode = True
import documentation as doc
import manage

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('version','category','kind','topic','title','request'):
        parser.add_argument('--'+field, required=True, choices=sorted(doc.KINDS) if field=='kind' else None)
    parser.add_argument('--area', default='')
    parser.add_argument('--related', action='append', default=[], help='Other registered version ID; repeat as needed')
    parser.add_argument('--supersedes', help='Archive-relative predecessor; reuse its version/category/area/topic/kind')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if manage.refresh(check=True): raise ValueError('Resolve existing archive errors before creating a document')
    _, versions, _ = manage.configs()
    by_id = {v['id']:v for v in versions}
    v = by_id.get(args.version)
    if not v or not v.get('authorable', False): raise ValueError('Unknown/non-authorable version')
    if args.category not in v['documentCategories']: raise ValueError('Unregistered category')
    if not doc.slug(args.topic,32) or len(args.topic)<2 or (args.area and not doc.slug(args.area,32)):
        raise ValueError('Use short lowercase slugs for topic/area')
    stamp = doc.now()
    d = dict(schema=1,document_id='',title=args.title,kind=args.kind,version_id=v['id'],version_name=v['versionName'],
             version_code=v['versionCode'],track=v['documentTrack'],category=args.category,area=args.area,topic=args.topic,
             created_at=stamp,updated_at=stamp,revision=1,status='draft',supersedes=None,
             related_versions=args.related,source_request=args.request)
    if args.supersedes:
        prior = doc.validate(root,args.supersedes,versions)
        for key in ('version_id','category','area','topic','kind'):
            if d[key] != prior[key]: raise ValueError('Revision must retain predecessor '+key)
        d.update(created_at=prior['created_at'],revision=prior['revision']+1,supersedes=args.supersedes)
    date = d['created_at'].split('T')[0]
    d['document_id']='/'.join([v['id'],args.category,args.area,args.topic,args.kind,date])
    rel = doc.location(v,d)
    dest = doc.inside(root,rel)
    if len(str(dest)) > 240: raise ValueError('Path exceeds 240 characters; shorten topic/area')
    if dest.exists(): raise ValueError('Destination exists; edit the draft or deliberately create a revision: '+rel)
    body = (root/'docs/maintainers/templates'/f'{args.kind}.md').read_text(encoding='utf-8').replace('{{TITLE}}',args.title)
    dest.parent.mkdir(parents=True,exist_ok=True)
    with dest.open('x',encoding='utf-8',newline='\n') as out: out.write(doc.serialize(d,body))
    try: doc.validate(root,rel,versions)
    except Exception:
        # Only our just-created file; never remove a pre-existing record.
        dest.unlink()
        raise
    if manage.refresh() or manage.refresh(check=True):
        raise ValueError('Draft retained at '+rel+'; resolve archive validation failure')
    print(str(dest))
    return 0

if __name__ == '__main__':
    try: sys.exit(main())
    except (ValueError, OSError, KeyError) as exc:
        print('Error: '+str(exc),file=sys.stderr);sys.exit(1)
