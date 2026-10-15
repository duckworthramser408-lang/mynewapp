import copy
import ctypes
import json
from pathlib import Path
import unittest
from tools.g23_dataset import validate_parameters

ROOT=Path(__file__).resolve().parents[1]
class CoreTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.lib=ctypes.CDLL(str(ROOT/'.build/libg23.so'))
  cls.lib.g23_render.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_char_p,ctypes.c_void_p,ctypes.c_size_t]
  cls.defaults=json.loads((ROOT/'protocol/default_parameters.json').read_text())
  cls.w,cls.h=73,51
  # Hue/brightness ramps and nonuniform texture exercise all channels/zones.
  cls.image=bytes(v for y in range(cls.h) for x in range(cls.w) for v in ((x*17+y*7)%256,(x*9+y*23)%256,(x*31+y*13)%256,255))
 def render(self,doc=None,image=None,w=None,h=None,raw=None):
  image=image or self.image;w=w or self.w;h=h or self.h
  output=ctypes.create_string_buffer(len(image));error=ctypes.create_string_buffer(256)
  code=self.lib.g23_render(image,output,w,h,raw or json.dumps(doc or self.defaults).encode(),error,256)
  if code:raise ValueError(error.value.decode())
  return output.raw
 def test_neutral_preserves_all_bytes(self):self.assertEqual(self.render(),self.image)
 def test_each_adjustment_changes_pixels(self):
  paths=[]
  for k in ['exposure_ev','contrast','highlights','shadows','whites','blacks','temperature_shift','tint','vibrance','saturation']:paths.append(([k],1 if k=='exposure_ev' else 65))
  for color in self.defaults['parameters']['hsl']:
   for key in ['hue','saturation','luminance']:paths.append((['hsl',color,key],70))
  for zone in ['shadows','midtones','highlights']:
   paths.append((['color_grading',zone,'saturation'],80));paths.append((['color_grading',zone,'luminance'],50))
  for name in ['sharpness','clarity','local_contrast','grain_amount','vignette','fade']:paths.append((['texture',name],75))
  for path,value in paths:
   with self.subTest(path=path):
    doc=copy.deepcopy(self.defaults);p=doc['parameters']
    for part in path[:-1]:p=p[part]
    p[path[-1]]=value
    self.assertNotEqual(self.render(doc),self.image)
 def test_curves_channels(self):
  for channel in ['rgb','red','green','blue']:
   d=copy.deepcopy(self.defaults);d['parameters']['tone_curves'][channel]=[[0,0],[.4,.2],[.7,.85],[1,1]]
   self.assertNotEqual(self.render(d),self.image)
 def test_grain_seed_reproducible_and_size_active(self):
  d=copy.deepcopy(self.defaults);d['parameters']['texture']['grain_amount']=80
  a=self.render(d);self.assertEqual(a,self.render(d))
  d['parameters']['texture']['grain_seed']=999;self.assertNotEqual(a,self.render(d))
  d['parameters']['texture']['grain_seed']=12345;d['parameters']['texture']['grain_size']=80;self.assertNotEqual(a,self.render(d))
 def test_grading_hue_balance_blending(self):
  d=copy.deepcopy(self.defaults);g=d['parameters']['color_grading'];g['shadows']['saturation']=80;g['highlights']['saturation']=70;g['highlights']['hue']=200
  a=self.render(d)
  for path,val in [('balance',80),('blending',95)]:
   other=copy.deepcopy(d);other['parameters']['color_grading'][path]=val;self.assertNotEqual(a,self.render(other))
  g['shadows']['hue']=120;self.assertNotEqual(a,self.render(d))
 def test_achromatic_hsl_no_color_cast(self):
  d=copy.deepcopy(self.defaults);d['parameters']['hsl']['red']['hue']=100
  gray=bytes([127,127,127,255])*16
  self.assertEqual(self.render(d,gray,4,4),gray)
 def test_invalid_json_and_parameters(self):
  for raw in [b'{}',b'{"x": NaN}',b'{"a":1,"a":2}',b'{"a":01}',b'{"a":1.}',b'[]']:
   with self.assertRaises(ValueError):self.render(raw=raw)
  for path,val in [('exposure_ev',6),('temperature_shift',101)]:
   d=copy.deepcopy(self.defaults);d['parameters'][path]=val
   with self.assertRaises(ValueError):self.render(d)
  d=copy.deepcopy(self.defaults);d['parameters']['tone_curves']['red']=[[0,0],[0,.5],[1,1]]
  with self.assertRaises(ValueError):self.render(d)
 def test_schema_and_identity_geometry(self):
  validate_parameters(self.defaults)
  d=copy.deepcopy(self.defaults);d['parameters']['geometry']['crop']=[0,0,.5,1]
  validate_parameters(d)
  with self.assertRaises(ValueError):self.render(d)
 def test_reject_unknown_versions_and_fractional_seed(self):
  d=copy.deepcopy(self.defaults);d['render_pipeline_version']='2.0.0'
  with self.assertRaises(ValueError):self.render(d)
  d=copy.deepcopy(self.defaults);d['parameters']['texture']['grain_seed']=.5
  with self.assertRaises(ValueError):self.render(d)
 def test_multi_tile_neutral(self):
  w,h=519,269;image=bytes([23,97,214,255])*w*h
  self.assertEqual(self.render(image=image,w=w,h=h),image)
 def test_reject_extra_fields_and_changed_order(self):
  d=copy.deepcopy(self.defaults);d['parameters']['unversioned_knob']=4
  with self.assertRaises(ValueError):self.render(d)
  d=copy.deepcopy(self.defaults);d['processing_order'].reverse()
  with self.assertRaises(ValueError):self.render(d)
 def test_spatial_filter_across_tile_boundaries(self):
  w,h=529,277
  image=bytes(v for y in range(h) for x in range(w) for v in ((x*13+y*7)%256,(x*3+y*17)%256,(x*19+y*11)%256,255))
  d=copy.deepcopy(self.defaults);d['parameters']['texture']['local_contrast']=60
  output=self.render(d,image,w,h);radius=max(4,min(128,int(min(w,h)/32)))
  for x,y in [(255,127),(256,128),(511,255),(512,256),(0,0),(528,276)]:
   for k in range(3):
    neighbors=[image[4*(yy*w+xx)+k]/255 for yy in range(max(0,y-radius),min(h,y+radius+1)) for xx in range(max(0,x-radius),min(w,x+radius+1))]
    c=image[4*(y*w+x)+k]/255;expected=round(max(0,min(1,c+.3*(c-sum(neighbors)/len(neighbors))))*255)
    self.assertLessEqual(abs(output[4*(y*w+x)+k]-expected),1)
if __name__=='__main__':unittest.main()
