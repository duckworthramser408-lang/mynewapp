import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from PIL import Image
from tools.render import render_file
from tools.g23_dataset import DEFAULT,read_json,sha256,export_dataset,analyze

class ImageWorkflowTests(unittest.TestCase):
 def test_real_codec_pair_render_and_archive(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);samples=root/'samples';samples.mkdir();folder=samples/'G23_image';folder.mkdir()
   original=Image.new('RGB',(120,80));original.putdata([(x*2,y*3,(x+y)%256) for y in range(80) for x in range(120)]);original.save(folder/'original.jpg',quality=98)
   original_hash=sha256(folder/'original.jpg')
   doc=read_json(DEFAULT);doc['parameters']['exposure_ev']=-.4;doc['parameters']['hsl']['green']['hue']=-30;doc['parameters']['tone_curves']['rgb']=[[0,0],[.5,.45],[1,1]];doc['parameters']['texture']['grain_amount']=20
   self.assertEqual(render_file(folder/'original.jpg',folder/'edited.png',doc),(120,80))
   first=sha256(folder/'edited.png');render_file(folder/'original.jpg',folder/'edited.png',doc);self.assertEqual(sha256(folder/'edited.png'),first)
   self.assertEqual(sha256(folder/'original.jpg'),original_hash)
   with Image.open(folder/'edited.png') as output:
    self.assertEqual(output.size,(120,80));self.assertIn('icc_profile',output.info)
    self.assertNotEqual(output.tobytes(),original.tobytes());output.resize((30,20)).save(folder/'thumbnail_after.jpg')
   original.resize((30,20)).save(folder/'thumbnail_before.jpg')
   (folder/'parameters.json').write_text(json.dumps(doc))
   hashes={name:sha256(folder/name) for name in ('original.jpg','edited.png','thumbnail_before.jpg','thumbnail_after.jpg','parameters.json')}
   (folder/'metadata.json').write_text(json.dumps({'schema_version':'1.0','sample_id':folder.name,'status':'approved','capture':{'device_model':'synthetic-test-fixture'},'scene':{'lighting':[],'subjects':[],'rating':4},'sha256':hashes}))
   export_dataset(samples,root/'data.zip')
   with zipfile.ZipFile(root/'data.zip') as z:z.extractall(root/'unpacked')
   stats=analyze(root/'unpacked/G23_Dataset_Export',root/'report.html');self.assertEqual(stats['exposure_ev']['mean'],-.4)
 def test_real_lut_capture_rerender_export_keeps_source_and_bound_look(self):
  import shutil
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);samples=root/'samples';folder=samples/'G23_lut_photo';folder.mkdir(parents=True)
   original=Image.new('RGB',(80,60));original.putdata([(x*3,y*4,(x*7+y*13)%256) for y in range(60) for x in range(80)]);original.save(folder/'original.jpg',quality=98)
   original_hash=sha256(folder/'original.jpg');catalog=read_json(DEFAULT.parents[1]/'looks/luts/catalog.json');item=next(v for v in catalog['luts'] if v['name']=='理光GR')
   shutil.copyfile(DEFAULT.parents[1]/'looks/luts'/item['file'],folder/'look.cube')
   look={'look_pipeline_version':'cube_srgb_trilinear_1.0','id':item['id'],'name':item['name'],'sha256':item['sha256'],'file':'look.cube','grid_size':33,'input_color_space':'sRGB','intensity':1.0,'applied_after':'g23_parameters_1.0.0'}
   (folder/'look.json').write_text(json.dumps(look));doc=read_json(DEFAULT);(folder/'parameters.json').write_text(json.dumps(doc))
   render_file(folder/'original.jpg',root/'neutral.png',doc)
   render_file(folder/'original.jpg',folder/'edited.png',doc,folder/'look.cube');first=sha256(folder/'edited.png')
   self.assertNotEqual(first,sha256(root/'neutral.png'))
   render_file(folder/'original.jpg',folder/'edited.png',doc,folder/'look.cube');self.assertEqual(first,sha256(folder/'edited.png'));self.assertEqual(original_hash,sha256(folder/'original.jpg'))
   with Image.open(folder/'edited.png') as edited:edited.resize((20,15)).save(folder/'thumbnail_after.jpg')
   original.resize((20,15)).save(folder/'thumbnail_before.jpg')
   names=('original.jpg','edited.png','thumbnail_before.jpg','thumbnail_after.jpg','parameters.json','look.json','look.cube')
   meta={'schema_version':'1.0','sample_id':folder.name,'status':'draft','capture':{'device_model':'test-fixture'},'scene':{'rating':None},'sha256':{name:sha256(folder/name) for name in names}}
   (folder/'metadata.json').write_text(json.dumps(meta));export_dataset(samples,root/'look.zip',approved_only=False)
   with zipfile.ZipFile(root/'look.zip') as z:
    z.extractall(root/'out');row=json.loads(z.read('G23_Dataset_Export/dataset.jsonl'));self.assertEqual(row['look'],'samples/G23_lut_photo/look.json');self.assertTrue(row['lut'].endswith('/look.cube'))
   analyze(root/'out/G23_Dataset_Export',root/'report.html')
 def test_crop_rotation_and_exif_orientation(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);source=Image.new('RGB',(80,40),'red');exif=Image.Exif();exif[274]=6;source.save(root/'original.jpg',exif=exif)
   doc=read_json(DEFAULT)
   self.assertEqual(render_file(root/'original.jpg',root/'out.png',doc),(40,80))
   doc['parameters']['geometry']={'crop':[0,0,.5,1],'rotation_degrees':90}
   self.assertEqual(render_file(root/'original.jpg',root/'out.png',doc),(80,20))
if __name__=='__main__':unittest.main()
