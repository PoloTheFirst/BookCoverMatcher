# SPDX-License-Identifier: GPL-3.0-or-later
"""Restricted Markdown metadata, filing and revision checks. Python stdlib only."""
from pathlib import Path
import datetime as dt
import json
import re

KINDS = {'prompt': 'prompts', 'report': 'reports', 'guide': 'guides', 'handoff': 'handoffs'}
STATES = {'draft', 'ready-for-review', 'final', 'blocked'}
FROZEN = {'final', 'blocked'}
FIELDS = {'schema', 'document_id', 'title', 'kind', 'version_id', 'version_name',
          'version_code', 'track', 'category', 'area', 'topic', 'created_at',
          'updated_at', 'revision', 'status', 'supersedes', 'related_versions', 'source_request'}
HK = dt.timezone(dt.timedelta(hours=8), 'Asia/Hong_Kong')

def now():
    return dt.datetime.now(HK).isoformat(timespec='seconds')

def slug(value, limit=48):
    return isinstance(value, str) and len(value) <= limit and bool(re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', value))

def inside(root, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative:
        raise ValueError('Use an archive-relative forward-slash path')
    p = Path(relative)
    if p.is_absolute() or '..' in p.parts or p.as_posix() != relative:
        raise ValueError('Unsafe/noncanonical relative path: '+relative)
    result = root/p
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError('Path leaves archive: '+relative)
    for part in [result, *result.parents]:
        if part == root: break
        if part.is_symlink() or (hasattr(part, 'is_junction') and part.is_junction()):
            raise ValueError('Linked path is not an authoring destination: '+relative)
    return result

def metadata(text):
    lines = text.splitlines()
    if not lines or lines[0] != '---':
        raise ValueError('Missing structured frontmatter; use tools/new-document.py')
    try: end = lines.index('---', 1)
    except ValueError: raise ValueError('Frontmatter has no closing delimiter') from None
    data = {}
    for line in lines[1:end]:
        key, sep, value = line.partition(':')
        if not sep or key in data or key not in FIELDS:
            raise ValueError('Invalid/duplicate metadata key: '+key)
        try: data[key] = json.loads(value)
        except json.JSONDecodeError: raise ValueError('Metadata values must use JSON syntax: '+key) from None
    if set(data) != FIELDS:
        raise ValueError('Missing metadata fields: '+', '.join(sorted(FIELDS-set(data))))
    return data, '\n'.join(lines[end+1:])

def serialize(data, body):
    return '---\n'+''.join(k+': '+json.dumps(v, ensure_ascii=False)+'\n' for k,v in data.items())+'---\n\n'+body.lstrip()

def registry_errors(versions):
    errors, keys = [], set()
    for v in versions:
        if 'documentKey' not in v: continue
        key = v['documentKey']
        if not slug(key, 64) or key in keys: errors.append('Invalid/duplicate documentKey: '+str(key))
        keys.add(key)
        if not slug(v.get('documentTrack')): errors.append('Invalid documentTrack: '+v['id'])
        cats = v.get('documentCategories')
        if not isinstance(cats, dict) or not cats:
            errors.append('Missing documentCategories: '+v['id']); continue
        if len(set(cats.values())) != len(cats): errors.append('Duplicate category directories: '+v['id'])
        for category, rel in cats.items():
            if not slug(category) or not isinstance(rel, str) or not all(slug(s) for s in rel.split('/')):
                errors.append('Invalid category mapping: '+v['id']+'/'+str(category))
    return errors

def location(v, d):
    date = dt.datetime.fromisoformat(d['created_at']).astimezone(HK).date().isoformat()
    category = v['documentCategories'][d['category']]
    filename = f"{date}__{v['documentKey']}__{d['category']}__{d['topic']}__{d['kind']}__r{d['revision']:02d}.md"
    parts = [v['root'], category]
    if d['area']: parts.append(d['area'])
    return '/'.join(parts+[KINDS[d['kind']], filename])

def validate(root, rel, versions):
    d, body = metadata(inside(root, rel).read_text(encoding='utf-8-sig'))
    by_id = {v['id']:v for v in versions}
    if d['schema'] != 1 or d['version_id'] not in by_id: raise ValueError('Unknown document schema/version ID')
    v = by_id[d['version_id']]
    if 'documentKey' not in v: raise ValueError('Version is not an authoring destination')
    if d['version_name'] != v['versionName'] or d['version_code'] != v['versionCode'] or d['track'] != v['documentTrack']:
        raise ValueError('Version name/code/track does not match versions.json')
    if d['kind'] not in KINDS or d['status'] not in STATES: raise ValueError('Invalid kind/status')
    if d['category'] not in v['documentCategories']: raise ValueError('Unregistered category')
    if not slug(d['topic'],32) or len(d['topic']) < 2 or (d['area'] and not slug(d['area'],32)):
        raise ValueError('Invalid topic/area slug')
    if not isinstance(d['area'],str): raise ValueError('area must be a string')
    if type(d['revision']) is not int or not 1 <= d['revision'] <= 99: raise ValueError('revision must be 1..99')
    for key in ('title','source_request'):
        if not isinstance(d[key],str) or not d[key].strip() or '\n' in d[key]: raise ValueError('Invalid '+key)
    times = [dt.datetime.fromisoformat(d[k]) for k in ('created_at','updated_at')]
    if any(t.utcoffset() != dt.timedelta(hours=8) for t in times) or times[1] < times[0]:
        raise ValueError('Timestamps must use +08:00; updated_at cannot precede created_at')
    expected = location(v,d)
    if rel != expected: raise ValueError('Expected canonical path: '+expected)
    if len(str(root/rel)) > 240: raise ValueError('Path exceeds 240 characters; shorten topic/area')
    identity = '/'.join([d['version_id'], d['category'], d['area'], d['topic'], d['kind'], times[0].date().isoformat()])
    if d['document_id'] != identity: raise ValueError('document_id does not match logical identity')
    related = d['related_versions']
    if not isinstance(related,list) or any(not isinstance(x,str) or x not in by_id or x==v['id'] for x in related) or len(related)!=len(set(related)):
        raise ValueError('related_versions must contain distinct other registered IDs')
    if d['supersedes']:
        prior, _ = metadata(inside(root,d['supersedes']).read_text(encoding='utf-8-sig'))
        if d['supersedes']==rel or prior['document_id']!=d['document_id'] or prior['revision']+1!=d['revision'] or prior['created_at']!=d['created_at']:
            raise ValueError('Revision must follow the same document identity and creation date')
        if dt.datetime.fromisoformat(prior['updated_at']) > times[1]: raise ValueError('Revision predates its predecessor')
    elif d['supersedes'] is not None or d['revision'] != 1:
        raise ValueError('r01 needs null supersedes; subsequent revisions need a predecessor')
    template = (root/'docs/maintainers/templates'/f"{d['kind']}.md").read_text(encoding='utf-8')
    for heading in re.findall(r'^## .+$', template, re.M):
        if heading not in body.splitlines(): raise ValueError('Missing required heading: '+heading)
    if d['status'] in FROZEN and re.search(r'(?im)\bTODO\b|\{\{[^}]+\}\}', body):
        raise ValueError('Complete template placeholders before final/blocked status')
    return d

def candidate(rel, originals, generated):
    if not rel.lower().endswith('.md') or rel in originals or rel in generated: return False
    return rel.startswith('docs/history/')

def audit(root, entries, previous, versions, originals, generated):
    errors = registry_errors(versions)
    current = {e['path']:e for e in entries}
    revisions = set()
    for e in entries:
        if not candidate(e['path'], originals, generated): continue
        try:
            d = validate(root, e['path'], versions)
            key = (d['document_id'],d['revision'])
            if key in revisions: raise ValueError('Duplicate logical document revision')
            revisions.add(key)
            e.update(version=d['version_id'],category=d['category'],phase=d['status']+' document r'+str(d['revision']),
                     assignmentBasis='Validated document metadata and registered path',document=d)
        except (ValueError,TypeError,KeyError,OSError) as exc:
            errors.append('Document '+e['path']+': '+str(exc))
    for rel, old in previous.items():
        if old.get('document',{}).get('status') in FROZEN:
            if rel not in current or old['sha256'] != current[rel]['sha256']:
                errors.append('Frozen record changed/removed; restore it and create a revision: '+rel)
    return errors
