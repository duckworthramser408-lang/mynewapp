#!/usr/bin/env python3
"""Host 12 MP pipeline comparison; measurements are not phone latency claims."""
import argparse
import ctypes
import hashlib
import io
import json
from pathlib import Path
import statistics
import time
from PIL import Image
from tools.import_luts import parse_cube

ROOT = Path(__file__).resolve().parents[1]

def benchmark(runs=3, width=4000, height=3000):
    core = ctypes.CDLL(str(ROOT / '.build/libg23.so'))
    core.g23_render.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t]
    core.g23_apply_lut.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_size_t, ctypes.POINTER(ctypes.c_float), ctypes.c_size_t, ctypes.c_int, ctypes.POINTER(ctypes.c_float), ctypes.POINTER(ctypes.c_float), ctypes.c_void_p, ctypes.c_size_t]
    catalog = json.loads((ROOT/'looks/luts/catalog.json').read_text())['luts']
    item = next(v for v in catalog if v['name'] == '理光GR')
    start = time.perf_counter()
    size, lo, hi, values = parse_cube((ROOT/'looks/luts'/item['file']).read_text('utf-8-sig'))
    table = (ctypes.c_float * len(values))(*values)
    minimum, maximum = (ctypes.c_float*3)(*lo), (ctypes.c_float*3)(*hi)
    load_ms = (time.perf_counter()-start)*1000
    noise=Image.effect_noise((width,height),40).convert('RGB')
    source = io.BytesIO(); noise.save(source,format='JPEG',quality=97);noise.close()
    source=source.getvalue()
    doc=(ROOT/'protocol/default_parameters.json').read_bytes()
    rows=[]; expected=None
    for mode in ('old','new'):
        for trial in range(runs):
            clock=time.perf_counter(); stamps={}
            def stage(name):
                nonlocal clock
                now=time.perf_counter();stamps[name]=round((now-clock)*1000,2);clock=now
            image=Image.open(io.BytesIO(source)).convert('RGBA')
            raw=ctypes.create_string_buffer(image.tobytes(),width*height*4)
            image.close();error=ctypes.create_string_buffer(256)
            stage('decode_and_buffer_ms')
            if mode=='old':
                output=ctypes.create_string_buffer(len(raw))
                assert core.g23_render(raw,output,width,height,doc,error,256)==0,error.value
                raw=output
            stage('base_pipeline_ms')
            assert core.g23_apply_lut(raw,width,height,width*4,table,len(values),size,minimum,maximum,error,256)==0,error.value
            stage('lut_ms')
            pixels=hashlib.sha256(raw.raw).hexdigest()
            if expected is None:expected=pixels
            assert pixels==expected,'The direct LUT path must match neutral base + LUT before encoding'
            # Exclude the correctness hash from measured encoding time.
            clock=time.perf_counter()
            image=Image.frombytes('RGBA',(width,height),raw.raw).convert('RGB')
            encoded=io.BytesIO();image.save(encoded,format='PNG' if mode=='old' else 'JPEG',quality=95)
            image.close();stage('encode_ms')
            rows.append(dict(mode=mode,trial=trial+1,**stamps,total_ms=round(sum(stamps.values()),2),bytes=encoded.tell()))
            del raw
    medians={mode:{key:round(statistics.median(row[key] for row in rows if row['mode']==mode),2)
                       for key in ('decode_and_buffer_ms','base_pipeline_ms','lut_ms','encode_ms','total_ms','bytes')}
             for mode in ('old','new')}
    return dict(description='Host CPU, Pillow/libjpeg/libpng, generated textured 12MP JPEG, Ricoh GR 33^3 LUT; old excludes lab statistics, hashes and per-photo LUT copy. Not a device measurement.',
                width=width,height=height,runs=runs,lut_cold_parse_ms=round(load_ms,2),pre_encode_pixels_identical=True,
                medians=medians,speedup=round(medians['old']['total_ms']/medians['new']['total_ms'],2),runs_detail=rows)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--runs',type=int,default=3);parser.add_argument('--output',type=Path,default=ROOT/'.build/film-benchmark.json')
    args=parser.parse_args();result=benchmark(args.runs);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False,indent=2))
