#!/usr/bin/env python3
"""Import genuine, pinned Google AAR weights and canonical topology (no model fabrication)."""
from pathlib import Path
import hashlib,json,urllib.request,zipfile,io
ROOT=Path(__file__).resolve().parents[1]
BASE='https://dl.google.com/dl/android/maven2/com/google/mediapipe/facemesh/0.10.14/'
EXPECTED={'face_detection_short_range.tflite':'bbff11cebd1eb27a1e004cae0b0e63ec8c551cbf34a4451148b4908b8db3eca8','face_landmark.tflite':'1055cb9d4a9ca8b8c688902a3a5194311138ba256bcc94e336d8373a5f30c814'}
def main():
    target=ROOT/'models/beauty';target.mkdir(parents=True,exist_ok=True)
    data=urllib.request.urlopen(BASE+'facemesh-0.10.14.aar',timeout=30).read()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name,sha in EXPECTED.items():
            content=archive.read('assets/'+name);assert hashlib.sha256(content).hexdigest()==sha
            (target/name).write_bytes(content)
    pom=urllib.request.urlopen(BASE+'facemesh-0.10.14.pom',timeout=30).read();(target/'SOURCE.pom').write_bytes(pom)
    root='https://raw.githubusercontent.com/google-ai-edge/mediapipe/v0.10.14/'
    license=urllib.request.urlopen(root+'LICENSE',timeout=30).read();(target/'LICENSE').write_bytes(license)
    obj=urllib.request.urlopen(root+'mediapipe/modules/face_geometry/data/canonical_face_model.obj',timeout=30).read()
    (target/'canonical_face_model.obj').write_bytes(obj)
    rows=[list(map(float,l.split()[1:])) for l in obj.decode().splitlines() if l.startswith('v ')];assert len(rows)==468
    # Right handed camera image coordinates: +x right, +y down, +z away.
    converted=[[x,-y,-z] for x,y,z in rows]
    mirror=[min(range(468),key=lambda j:(rows[j][0]+v[0])**2+(rows[j][1]-v[1])**2+(rows[j][2]-v[2])**2) for v in rows]
    assert all(mirror[mirror[i]]==i for i in range(468))
    content='// Generated from Google MediaPipe v0.10.14 canonical_face_model.obj; Apache-2.0.\n#pragma once\nstatic constexpr float kBeautyCanonical[468][3] = {\n'
    content+='\n'.join('    {'+', '.join(f'{v:.7f}f' for v in row)+'},' for row in converted)+'\n};\n'
    (ROOT/'core/src/beauty_canonical.h').write_text(content)
    (target/'mirror_indices.json').write_text(json.dumps(mirror)+'\n')
    manifest={'source':BASE+'facemesh-0.10.14.aar','distribution_version':'0.10.14','distribution_license':'Apache-2.0 (Google Maven POM)','aar_sha256':hashlib.sha256(data).hexdigest(),'models':{name:{'sha256':sha,'bytes':(target/name).stat().st_size} for name,sha in EXPECTED.items()},'canonical_sha256':hashlib.sha256(obj).hexdigest(),'landmarks':468,'head_segmentation':False}
    (target/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest))
if __name__=='__main__':main()
