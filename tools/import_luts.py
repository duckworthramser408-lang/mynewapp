#!/usr/bin/env python3
"""Import user-supplied .cube data without executing archive contents."""
import hashlib,json,math,sys,zipfile
from pathlib import Path,PurePosixPath

def parse_cube(text):
 if len(text)>8*1024*1024:raise ValueError('LUT exceeds size limit')
 size=None;lo=[0.,0.,0.];hi=[1.,1.,1.];values=[];seen=set()
 for raw in text.lstrip('\ufeff').splitlines():
  line=raw.split('#',1)[0].strip()
  if not line:continue
  fields=line.split();key=fields[0]
  if key=='TITLE':continue
  if key in ('LUT_3D_SIZE','DOMAIN_MIN','DOMAIN_MAX'):
   if key in seen:raise ValueError('duplicate LUT header')
   seen.add(key)
   if key=='LUT_3D_SIZE':
    if len(fields)!=2:raise ValueError('invalid size')
    size=int(fields[1])
    if not 2<=size<=65:raise ValueError('unsupported cube size')
   else:
    if len(fields)!=4:raise ValueError('invalid domain')
    v=list(map(float,fields[1:]));
    if key=='DOMAIN_MIN':lo=v
    else:hi=v
   continue
  if key[0].isalpha():raise ValueError('unsupported LUT directive')
  if size is None or len(fields)!=3:raise ValueError('invalid LUT data row')
  row=list(map(float,fields))
  if not all(math.isfinite(x) and abs(x)<=64 for x in row):raise ValueError('invalid LUT number')
  values.extend(row)
  if len(values)>size**3*3:raise ValueError('LUT has too many rows')
 if size is None or len(values)!=size**3*3:raise ValueError('LUT grid length mismatch')
 if not all(math.isfinite(a) and math.isfinite(b) and a<b for a,b in zip(lo,hi)):raise ValueError('invalid domain range')
 return size,lo,hi,values

def import_archive(archive,destination):
 dest=Path(destination);dest.mkdir(parents=True,exist_ok=True);entries=[]
 colors=['#D5B274','#D38A64','#B3BE9B','#A4B4A5','#BBA0A4','#BDC3A0','#CEA173','#6C999B','#B89D73','#C3936B','#B89A84','#A6B6A0','#929EAE','#C3AD8F','#8EA9A3']
 with zipfile.ZipFile(archive) as z:
  assert sum(i.file_size for i in z.infolist())<=64*1024*1024,'Archive exceeds import budget'
  for info in z.infolist():
   path=PurePosixPath(info.filename)
   if info.is_dir():continue
   if path.is_absolute() or '..' in path.parts or '\\' in info.filename:raise ValueError('Unsafe archive path')
   if path.suffix.lower()!='.cube':continue
   if info.file_size>8*1024*1024:raise ValueError('LUT exceeds size limit')
   data=z.read(info);size,lo,hi,values=parse_cube(data.decode('utf-8-sig'))
   sha=hashlib.sha256(data).hexdigest();key='cube_'+sha[:16];name=key+'.cube'
   (dest/name).write_bytes(data)
   entries.append({'id':key,'name':path.stem,'source_name':path.name,'file':name,'sha256':sha,'grid_size':size,'domain_min':lo,'domain_max':hi,'color':colors[len(entries)%len(colors)]})
 if not entries:raise ValueError('No valid .cube LUT found')
 manifest={'catalog_version':'1.0','source_archive_sha256':hashlib.sha256(Path(archive).read_bytes()).hexdigest(),'luts':entries}
 (dest/'catalog.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 print(f'Imported {len(entries)} LUTs, unchanged original bytes')
if __name__=='__main__':import_archive(sys.argv[1],sys.argv[2])
