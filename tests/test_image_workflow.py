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
 def test_crop_rotation_and_exif_orientation(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);source=Image.new('RGB',(80,40),'red');exif=Image.Exif();exif[274]=6;source.save(root/'original.jpg',exif=exif)
   doc=read_json(DEFAULT)
   self.assertEqual(render_file(root/'original.jpg',root/'out.png',doc),(40,80))
   doc['parameters']['geometry']={'crop':[0,0,.5,1],'rotation_degrees':90}
   self.assertEqual(render_file(root/'original.jpg',root/'out.png',doc),(80,20))
if __name__=='__main__':unittest.main()
