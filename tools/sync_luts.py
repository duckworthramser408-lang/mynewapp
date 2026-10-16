#!/usr/bin/env python3
"""Stage identical, hash-checked LUT resources for the native Harmony project."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'looks/luts';target=ROOT/'harmony/entry/src/main/resources/rawfile/luts'
target.mkdir(parents=True,exist_ok=True)
for item in json.loads((source/'catalog.json').read_text())['luts']:
 file=source/item['file']
 assert hashlib.sha256(file.read_bytes()).hexdigest()==item['sha256']
 shutil.copyfile(file,target/item['file'])
shutil.copyfile(source/'catalog.json',target/'catalog.json')
print('Harmony LUT resources synchronized')
