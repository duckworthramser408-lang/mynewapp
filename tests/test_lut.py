import ctypes as C
import hashlib,json,math,random,unittest
from pathlib import Path
from tools.import_luts import parse_cube
ROOT=Path(__file__).resolve().parents[1]
LIB=C.CDLL(str(ROOT/'.build/libg23.so'))
F=LIB.g23_apply_lut
F.argtypes=[C.POINTER(C.c_uint8),C.c_int,C.c_int,C.c_size_t,C.POINTER(C.c_float),C.c_size_t,C.c_int,C.POINTER(C.c_float),C.POINTER(C.c_float),C.c_char_p,C.c_size_t]
F.restype=C.c_int

def apply(pixels,table,size,lo=(0,0,0),hi=(1,1,1),width=None,height=1,stride=None):
 width=width or len(pixels)//4;stride=stride or width*4
 buffer=(C.c_uint8*len(pixels)).from_buffer_copy(pixels);grid=(C.c_float*len(table))(*table)
 error=C.create_string_buffer(256)
 code=F(buffer,width,height,stride,grid,len(table),size,(C.c_float*3)(*lo),(C.c_float*3)(*hi),error,256)
 return code,bytes(buffer),error.value

def cube(size,fn):
 return [v for b in range(size) for g in range(size) for r in range(size) for v in fn(r/(size-1),g/(size-1),b/(size-1))]

def reference(pixel,grid,size,lo,hi):
 coords=[max(0,min(1,(pixel[c]/255-lo[c])/(hi[c]-lo[c])))*(size-1) for c in range(3)]
 floor=[min(size-2,int(x)) for x in coords];fraction=[coords[c]-floor[c] for c in range(3)]
 result=[]
 for c in range(3):
  v=0
  for db in range(2):
   for dg in range(2):
    for dr in range(2):
     weight=(fraction[0] if dr else 1-fraction[0])*(fraction[1] if dg else 1-fraction[1])*(fraction[2] if db else 1-fraction[2])
     v+=weight*grid[((floor[2]+db)*size*size+(floor[1]+dg)*size+floor[0]+dr)*3+c]
  result.append(math.floor(max(0,min(1,v))*255+.5))
 return bytes(result+[pixel[3]])

class LutTests(unittest.TestCase):
 def test_identity_channel_order_and_alpha(self):
  data=bytes([v for i in range(256) for v in (i,255-i,(i*19)%256,i)])
  self.assertEqual(apply(data,cube(2,lambda r,g,b:(r,g,b)),2)[1],data)
  code,output,_=apply(data,cube(2,lambda r,g,b:(b,r,g)),2)
  self.assertEqual(code,0)
  self.assertEqual(output,bytes(v for i in range(0,len(data),4) for v in (data[i+2],data[i],data[i+1],data[i+3])))
 def test_domain_clamp_trilinear_and_output_clamp(self):
  table=cube(3,lambda r,g,b:(r*g,b*r,g*b))
  data=bytes([20,150,240,255,128,80,90,127,255,255,0,255])
  lo=(.2,.1,.3);hi=(.8,.9,.7);code,result,_=apply(data,table,3,lo,hi)
  expected=b''.join(reference(data[i:i+4],table,3,lo,hi) for i in range(0,len(data),4))
  self.assertEqual(code,0);self.assertTrue(all(abs(a-b)<=1 for a,b in zip(result,expected)))
  self.assertEqual(apply(bytes([10,50,200,99]),cube(2,lambda r,g,b:(-1,2,.5)),2)[1],bytes([0,255,128,99]))
 def test_reject_invalid_input_without_modifying_pixels(self):
  data=bytes([30,80,150,255]);table=cube(2,lambda r,g,b:(r,g,b))
  for grid,size,lo,hi in [(table[:-1],2,(0,0,0),(1,1,1)),(table,1,(0,0,0),(1,1,1)),([float('nan')]+table[1:],2,(0,0,0),(1,1,1)),(table,2,(0,1,0),(1,1,1)),(table,2,(0,0,0),(float('inf'),1,1))]:
   code,result,error=apply(data,grid,size,lo,hi);self.assertEqual(code,1);self.assertEqual(result,data);self.assertTrue(error)
 def test_preserve_row_padding(self):
  data=bytes([10,20,30,40,77,77,77,60,70,80,90,77,77,77])
  code,result,_=apply(data,cube(2,lambda r,g,b:(b,g,r)),2,width=1,height=2,stride=7)
  self.assertEqual(code,0);self.assertEqual(result,bytes([30,20,10,40,77,77,77,80,70,60,90,77,77,77]))
 def test_all_user_luts_match_independent_interpolation_and_hashes(self):
  catalog=json.loads((ROOT/'looks/luts/catalog.json').read_text());self.assertEqual(len(catalog['luts']),15)
  self.assertEqual(len({v['id'] for v in catalog['luts']}),15)
  randomizer=random.Random(314159);pixels=bytes([0,0,0,255,255,255,255,255]+[v for _ in range(64) for v in (randomizer.randrange(256),randomizer.randrange(256),randomizer.randrange(256),255)])
  for item in catalog['luts']:
   file=ROOT/'looks/luts'/item['file'];raw=file.read_bytes();self.assertEqual(hashlib.sha256(raw).hexdigest(),item['sha256'])
   size,lo,hi,grid=parse_cube(raw.decode('utf-8-sig'));self.assertEqual(size,33)
   code,result,_=apply(pixels,grid,size,lo,hi);self.assertEqual(code,0)
   expected=b''.join(reference(pixels[i:i+4],grid,size,lo,hi) for i in range(0,len(pixels),4))
   self.assertLessEqual(max(abs(a-b) for a,b in zip(result,expected)),1,item['name'])
   self.assertNotEqual(result,pixels,item['name'])
   self.assertEqual(result,apply(pixels,grid,size,lo,hi)[1])
 def test_cube_parser_rejects_truncated_and_1d_tables(self):
  for text in ('LUT_3D_SIZE 2\n0 0 0\n','LUT_1D_SIZE 2\n0 0 0\n1 1 1\n','LUT_3D_SIZE 1000\n'):
   with self.assertRaises((ValueError,AssertionError)):parse_cube(text)
