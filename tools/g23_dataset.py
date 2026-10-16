#!/usr/bin/env python3
"""Local G23 dataset validator/exporter/reader. Python stdlib; no upload.

Metadata stores final parameters separately from history. Export validates every
file, hashes streaming, and atomically replaces output only after success.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import html
import io
import json
import os
from pathlib import Path, PurePosixPath
import statistics
import shutil
import stat
import tempfile
import zipfile

VERSION = '1.0'
PIPELINE = '1.0.0'
DEFAULT = Path(__file__).resolve().parents[1] / 'protocol/default_parameters.json'
REQUIRED = ('original.jpg', 'edited.png', 'thumbnail_before.jpg', 'thumbnail_after.jpg', 'parameters.json', 'metadata.json')

def read_json(path):
    def unique(pairs):
        result = {}
        for k, v in pairs:
            if k in result:
                raise ValueError(f'duplicate JSON key: {k}')
            result[k] = v
        return result
    return json.loads(Path(path).read_text('utf-8'), object_pairs_hook=unique,
                      parse_constant=lambda s: (_ for _ in ()).throw(ValueError(s)))

def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()

def flatten(value, prefix=''):
    result = {}
    for k, v in value.items():
        key = f'{prefix}.{k}' if prefix else k
        if isinstance(v, dict): result.update(flatten(v, key))
        elif isinstance(v, (int, float)) and not isinstance(v, bool): result[key] = v
    return result

def validate_parameters(doc):
    schema = read_json(DEFAULT.parent / 'parameters.schema.json')
    def check(v, s, path):
        if 'oneOf' in s:
            for option in s['oneOf']:
                try: check(v, option, path); return
                except ValueError: pass
            raise ValueError(f'{path}: no valid variant')
        if 'const' in s and v != s['const']: raise ValueError(f'{path}: wrong version/order')
        if 'enum' in s and v not in s['enum']: raise ValueError(f'{path}: invalid enum')
        t = s.get('type')
        if t == 'object':
            if not isinstance(v, dict) or set(v) != set(s['required']): raise ValueError(f'{path}: fields mismatch')
            for k, prop in s['properties'].items(): check(v[k], prop, f'{path}.{k}')
        elif t == 'array':
            if not isinstance(v, list) or not s.get('minItems',0) <= len(v) <= s.get('maxItems',10**9): raise ValueError(f'{path}: array length')
            for i, item in enumerate(v):
                sub = s.get('prefixItems',[])
                check(item, sub[i] if i < len(sub) else s.get('items',{}), f'{path}[{i}]')
        elif t in ('number','integer'):
            if isinstance(v,bool) or not isinstance(v,(int,float)) or (t=='integer' and int(v)!=v) or not s['minimum']<=v<=s['maximum']: raise ValueError(f'{path}: number out of range')
        elif t == 'null' and v is not None: raise ValueError(f'{path}: expected null')
    check(doc, schema, 'parameters')
    for points in doc['parameters']['tone_curves'].values():
        if points[0][0] != 0 or points[-1][0] != 1 or any(b[0]-a[0]<.0001 for a,b in zip(points,points[1:])): raise ValueError('invalid curve ordering')
    crop = doc['parameters']['geometry']['crop']
    if crop and (crop[0]>=crop[2] or crop[1]>=crop[3]): raise ValueError('empty crop')
    return doc

def safe_file(root, relative):
    p = PurePosixPath(relative)
    if p.is_absolute() or '..' in p.parts or '\\' in relative: raise ValueError('unsafe file path')
    target = root.joinpath(*p.parts)
    if not target.resolve().is_relative_to(root.resolve()) or target.is_symlink(): raise ValueError('file escapes dataset')
    if not target.is_file(): raise ValueError(f'missing file: {relative}')
    return target

def parameter_values(value, prefix=''):
    result = {}
    for key, item in value.items():
        path = f'{prefix}.{key}' if prefix else key
        if isinstance(item, dict): result.update(parameter_values(item, path))
        else: result[path] = item
    return result

def validate_comparison(folder, sid, params):
    before = validate_parameters(read_json(safe_file(folder, 'original_parameters.json')))
    if before != read_json(DEFAULT): raise ValueError('original parameter baseline is not neutral')
    comparison = read_json(safe_file(folder, 'comparison.json'))
    if comparison.get('analysis_schema_version') != '1.0' or comparison.get('sample_id') != sid or comparison.get('render_pipeline_version') != PIPELINE:
        raise ValueError('comparison ID/version mismatch')
    if comparison['before']['parameters'] != before or comparison['after']['parameters'] != params:
        raise ValueError('comparison parameter snapshot mismatch')
    original = parameter_values(before['parameters']); edited = parameter_values(params['parameters'])
    pairs = comparison['parameter_pairs']
    if len(pairs) != len(original) or len({p['parameter'] for p in pairs}) != len(original):
        raise ValueError('comparison parameter coverage mismatch')
    for pair in pairs:
        key = pair['parameter']
        if key not in original or pair['before'] != original[key] or pair['after'] != edited[key]:
            raise ValueError('comparison parameter pair mismatch')
    return comparison

def inspect_sample(folder):
    if not folder.is_dir() or folder.is_symlink(): raise ValueError('invalid sample directory')
    meta = read_json(safe_file(folder, 'metadata.json'))
    sid = meta['sample_id']
    if not sid.startswith('G23_') or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in sid) or folder.name != sid: raise ValueError('sample ID/directory mismatch')
    if meta['schema_version'] != VERSION or meta['status'] not in ('draft','approved'): raise ValueError('unsupported metadata/status')
    rating = meta['scene'].get('rating')
    if rating is not None and (isinstance(rating,bool) or not isinstance(rating,int) or not 1<=rating<=5): raise ValueError('rating must be 1..5 or null')
    params = validate_parameters(read_json(safe_file(folder, 'parameters.json')))
    hashes = meta.get('sha256',{})
    names = list(REQUIRED)
    if (folder/'original.dng').is_file(): names.append('original.dng')
    sidecars = ('original_parameters.json', 'comparison.json')
    if any((folder/n).exists() or n in hashes for n in sidecars):
        names.extend(sidecars)
        validate_comparison(folder, sid, params)
    for n in names:
        file = safe_file(folder,n)
        if n != 'metadata.json' and (n not in hashes or sha256(file) != hashes[n]): raise ValueError(f'{sid}: checksum missing/mismatch: {n}')
    for n, signature in [('original.jpg',b'\xff\xd8\xff'),('edited.png',b'\x89PNG\r\n\x1a\n'),('thumbnail_before.jpg',b'\xff\xd8\xff'),('thumbnail_after.jpg',b'\xff\xd8\xff')]:
        with (folder/n).open('rb') as f:
            if f.read(len(signature))!=signature: raise ValueError(f'{sid}: invalid image type {n}')
    from PIL import Image
    for n in ('original.jpg','edited.png','thumbnail_before.jpg','thumbnail_after.jpg'):
        with Image.open(folder/n) as image:
            image.verify()
    if 'original.dng' in names:
        with (folder/'original.dng').open('rb') as f:
            if f.read(4) not in (b'II*\x00',b'MM\x00*'): raise ValueError('RAW must be TIFF/DNG, never fabricated')
    return meta, params, names

def export_dataset(source, destination, approved_only=True, progress=lambda done,total:None):
    source,destination = Path(source),Path(destination)
    folders = sorted(p for p in source.iterdir() if p.is_dir() and p.name.startswith('G23_'))
    selected=[]; ids=set(); originals=set()
    for folder in folders:
        meta,params,names=inspect_sample(folder)
        if approved_only and meta['status']!='approved': continue
        if meta['sample_id'] in ids: raise ValueError('duplicate ID')
        original=meta['sha256']['original.jpg']
        if original in originals: raise ValueError('duplicate original capture')
        ids.add(meta['sample_id']);originals.add(original);selected.append((folder,meta,params,names))
    if not selected: raise ValueError('no matching samples')
    destination.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix='.g23-',suffix='.zip',dir=destination.parent);os.close(fd)
    rows=[]; summary=[]; paired=[]; hashes={}; prefix='G23_Dataset_Export/'
    try:
        with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_DEFLATED,allowZip64=True) as z:
            for i,(folder,meta,params,names) in enumerate(selected):
                sid=meta['sample_id'];rel=f'samples/{sid}/'
                for name in names:
                    if name == 'metadata.json':
                        data=json.dumps(meta,ensure_ascii=False,allow_nan=False).encode()
                        z.writestr(prefix+rel+name,data);hashes[rel+name]=hashlib.sha256(data).hexdigest()
                    else:
                        digest=hashlib.sha256()
                        with (folder/name).open('rb') as source_file, z.open(prefix+rel+name,'w',force_zip64=True) as archive_file:
                            for block in iter(lambda:source_file.read(1024*1024),b''):
                                archive_file.write(block);digest.update(block)
                        hashes[rel+name]=digest.hexdigest()
                        if hashes[rel+name]!=meta['sha256'][name]:raise ValueError('source changed during export')
                rows.append({'sample_id':sid,'original':rel+'original.jpg','edited':rel+'edited.png','parameters':rel+'parameters.json','metadata':rel+'metadata.json','scene':meta['scene'],'device_model':meta['capture'].get('device_model'),'rating':meta['scene'].get('rating'),'approved':meta['status']=='approved','render_pipeline_version':PIPELINE})
                if 'comparison.json' in names:
                    rows[-1].update(original_parameters=rel+'original_parameters.json', comparison=rel+'comparison.json')
                    comparison=read_json(folder/'comparison.json')
                    paired.extend({'sample_id':sid, 'parameter':p['parameter'], 'label':p['label'],
                                   'before':json.dumps(p['before'],ensure_ascii=False,separators=(',',':')),
                                   'after':json.dumps(p['after'],ensure_ascii=False,separators=(',',':'))} for p in comparison['parameter_pairs'])
                summary.append({'sample_id':sid,**flatten(params['parameters']),'curves_file':rel+'parameters.json'})
                progress(i+1,len(selected))
            z.writestr(prefix+'dataset.jsonl',''.join(json.dumps(row,ensure_ascii=False,allow_nan=False)+'\n' for row in rows))
            stream=io.StringIO(newline='');writer=csv.DictWriter(stream,fieldnames=list(summary[0]));writer.writeheader();writer.writerows(summary)
            z.writestr(prefix+'reports/parameters_summary.csv',stream.getvalue())
            if paired:
                stream=io.StringIO(newline=''); writer=csv.DictWriter(stream,fieldnames=['sample_id','parameter','label','before','after'])
                writer.writeheader();writer.writerows(paired)
                z.writestr(prefix+'reports/before_after_parameters.csv',stream.getvalue())
            z.writestr(prefix+'reports/dataset_summary.json',json.dumps({'count':len(rows),'approved':sum(r['approved'] for r in rows),'pipeline':PIPELINE}))
            z.writestr(prefix+'manifest.json',json.dumps({'schema_version':VERSION,'sample_count':len(rows),'sha256':hashes},indent=2))
            z.writestr(prefix+'README.md','# G23 Dataset\nSDR sRGB, original captures retained. Final labels only; history excluded.\nVerify manifest hashes before training. No AI model is included.\n')
        os.replace(tmp,destination)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)
    return len(rows)

def analyze(root, output):
    root=Path(root);manifest=read_json(root/'manifest.json')
    for rel,expected in manifest['sha256'].items():
        if sha256(safe_file(root,rel))!=expected: raise ValueError(f'checksum mismatch: {rel}')
    rows=[json.loads(line) for line in (root/'dataset.jsonl').read_text().splitlines() if line.strip()]
    if not rows or len(rows)!=manifest['sample_count']: raise ValueError('manifest count mismatch')
    ids=set(); originals=set(); values={}; cards=[]
    for row in rows:
        if row['sample_id'] in ids: raise ValueError('duplicate ID')
        ids.add(row['sample_id'])
        for key in ('original','edited','parameters','metadata'):
            if row[key] not in manifest['sha256']: raise ValueError('unverified referenced file')
            safe_file(root,row[key])
        sid=row['sample_id']
        expected_prefix=f'samples/{sid}/'
        for key,name in [('original','original.jpg'),('edited','edited.png'),('parameters','parameters.json'),('metadata','metadata.json')]:
            if row[key]!=expected_prefix+name: raise ValueError('sample paths do not match ID')
        meta=read_json(safe_file(root,row['metadata']))
        if meta['sample_id']!=sid or row['approved']!=(meta['status']=='approved'): raise ValueError('sample metadata mismatch')
        original_hash=manifest['sha256'][row['original']]
        if original_hash in originals: raise ValueError('duplicate original')
        originals.add(original_hash)
        for name,expected in meta['sha256'].items():
            if manifest['sha256'].get(expected_prefix+name)!=expected: raise ValueError('metadata/manifest mismatch')
        params=validate_parameters(read_json(safe_file(root,row['parameters'])))
        if row['render_pipeline_version']!=params['render_pipeline_version']: raise ValueError('pipeline mismatch')
        for key,v in flatten(params['parameters']).items(): values.setdefault(key,[]).append(v)
        # Relative references to files, no image Base64 and no image decode required.
        before=os.path.relpath(safe_file(root,row['original']),Path(output).parent)
        after=os.path.relpath(safe_file(root,row['edited']),Path(output).parent)
        table=''
        if 'comparison' in row or 'original_parameters' in row:
            for key, name in [('comparison','comparison.json'),('original_parameters','original_parameters.json')]:
                if row.get(key)!=expected_prefix+name or row[key] not in manifest['sha256']:
                    raise ValueError('unverified comparison reference')
                safe_file(root,row[key])
            comparison=validate_comparison(root/expected_prefix,sid,params)
            table='<details><summary>原始与调色参数</summary><table><tr><th>参数</th><th>原始</th><th>调色后</th></tr>'
            for pair in comparison['parameter_pairs']:
                table+='<tr>'+''.join('<td>'+html.escape(json.dumps(pair[k],ensure_ascii=False) if k!='label' else pair[k])+'</td>' for k in ('label','before','after'))+'</tr>'
            table+='</table></details>'
        cards.append(f'<section><h2>{html.escape(row["sample_id"])}</h2><div class="pair"><figure><figcaption>原图</figcaption><img src="{html.escape(before,quote=True)}"></figure><figure><figcaption>调色后</figcaption><img src="{html.escape(after,quote=True)}"></figure></div>{table}</section>')
    stats={k:{'min':min(v),'max':max(v),'mean':statistics.mean(v),'count':len(v)} for k,v in values.items()}
    Path(output).write_text('<!doctype html><meta charset="utf-8"><title>G23 pairs</title><style>body{background:#141414;color:#ddd;font:14px sans-serif}.pair{display:flex;gap:12px}figure{width:48%;margin:0}img{width:100%;height:260px;object-fit:contain}table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #444;text-align:left}summary{cursor:pointer;padding:16px 0}</style>'+''.join(cards)+'<pre>'+html.escape(json.dumps(stats,indent=2))+'</pre>',encoding='utf-8')
    return stats

def analyze_zip(archive, report):
    """Extract streaming into a fresh retained report-assets directory, then verify."""
    report=Path(report);report.parent.mkdir(parents=True,exist_ok=True)
    assets=Path(tempfile.mkdtemp(prefix='g23-report-assets-',dir=report.parent))
    try:
        with zipfile.ZipFile(archive) as z:
            names=set();total=0
            for entry in z.infolist():
                path=PurePosixPath(entry.filename)
                if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or (stat.S_IFMT(entry.external_attr>>16)==stat.S_IFLNK): raise ValueError('unsafe ZIP member')
                if entry.filename in names: raise ValueError('duplicate ZIP member')
                names.add(entry.filename);total+=entry.file_size
                if not path.parts or path.parts[0]!='G23_Dataset_Export': raise ValueError('unexpected archive root')
            if total+64*1024*1024>shutil.disk_usage(assets).free: raise ValueError('not enough disk for dataset')
            for entry in z.infolist():
                target=assets.joinpath(*PurePosixPath(entry.filename).parts)
                if not target.resolve().is_relative_to(assets.resolve()): raise ValueError('unsafe path')
                if entry.is_dir(): target.mkdir(parents=True,exist_ok=True);continue
                target.parent.mkdir(parents=True,exist_ok=True)
                with z.open(entry) as source, target.open('xb') as dest: shutil.copyfileobj(source,dest,1024*1024)
        return analyze(assets/'G23_Dataset_Export',report)
    except BaseException:
        shutil.rmtree(assets);raise

def main():
    p=argparse.ArgumentParser();s=p.add_subparsers(dest='command',required=True)
    e=s.add_parser('export');e.add_argument('source',type=Path);e.add_argument('zip',type=Path);e.add_argument('--include-drafts',action='store_true')
    a=s.add_parser('analyze');a.add_argument('root',type=Path);a.add_argument('--report',type=Path,default=Path('g23-report.html'))
    args=p.parse_args()
    if args.command=='export':print(export_dataset(args.source,args.zip,not args.include_drafts,lambda n,t:print(f'{n}/{t}')))
    else:print(json.dumps((analyze_zip if args.root.is_file() else analyze)(args.root,args.report),indent=2))
if __name__=='__main__':main()
