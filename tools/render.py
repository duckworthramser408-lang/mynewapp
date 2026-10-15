#!/usr/bin/env python3
"""Desktop reference adapter: color-managed sRGB/EXIF decode, shared C++ core, PNG output."""
import argparse
import copy
import ctypes
from pathlib import Path
from PIL import Image, ImageCms, ImageOps
from tools.g23_dataset import read_json, validate_parameters
ROOT=Path(__file__).resolve().parents[1]

def render_file(source, destination, document):
    validate_parameters(document)
    with Image.open(source) as original:
        original.load()
        image=ImageOps.exif_transpose(original)
        profile=original.info.get('icc_profile')
        if profile:
            import io
            image=ImageCms.profileToProfile(image,ImageCms.ImageCmsProfile(io.BytesIO(profile)),ImageCms.createProfile('sRGB'),outputMode='RGB')
        else:
            image=image.convert('RGB')  # untagged SDR interpreted as sRGB, recorded in docs
        geometry=document['parameters']['geometry'];crop=geometry['crop']
        if crop:
            left,top,right,bottom=crop;w,h=image.size
            image=image.crop((int(left*w),int(top*h),max(int(left*w)+1,int(right*w)),max(int(top*h)+1,int(bottom*h))))
        if geometry['rotation_degrees']:
            image=image.rotate(-geometry['rotation_degrees'],expand=True)
        image=image.convert('RGBA');width,height=image.size
        raw=image.tobytes()
    identity=copy.deepcopy(document);identity['parameters']['geometry']={'crop':None,'rotation_degrees':0}
    core=ctypes.CDLL(str(ROOT/'.build/libg23.so'))
    core.g23_render.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_char_p,ctypes.c_void_p,ctypes.c_size_t]
    output=ctypes.create_string_buffer(len(raw));error=ctypes.create_string_buffer(256)
    status=core.g23_render(raw,output,width,height,__import__('json').dumps(identity).encode(),error,256)
    if status:raise ValueError(error.value.decode())
    rendered=Image.frombytes('RGBA',(width,height),output.raw).convert('RGB')
    rendered.save(destination,format='PNG',icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes())
    return width,height

def main():
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('parameters',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();print(render_file(a.source,a.output,read_json(a.parameters)))
if __name__=='__main__':main()
