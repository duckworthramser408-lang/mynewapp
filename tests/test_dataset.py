import copy
import io
from PIL import Image
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from tools.g23_dataset import export_dataset,sha256,analyze,analyze_zip,validate_parameters,read_json,DEFAULT

# Real codec fixtures remain tiny; algorithm/EXIF coverage is in test_image_workflow.
class DatasetTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.samples=self.root/'samples';self.samples.mkdir()
 def tearDown(self):self.tmp.cleanup()
 def sample(self,sid='G23_001',status='approved',original=b'\xff\xd8\xffcapture-a'):
  f=self.samples/sid;f.mkdir()
  def encoded(kind,color):
   out=io.BytesIO();Image.new('RGB',(8,6),color).save(out,format=kind);return out.getvalue()
  jpeg=encoded('JPEG',(sum(original)%256,80,40))
  files={'original.jpg':jpeg,'edited.png':encoded('PNG',(90,100,110)),'thumbnail_before.jpg':jpeg,'thumbnail_after.jpg':encoded('JPEG',(90,100,110)),'parameters.json':DEFAULT.read_bytes()}
  for name,data in files.items():(f/name).write_bytes(data)
  meta={'schema_version':'1.0','sample_id':sid,'status':status,'capture':{'device_model':'test-device'},'scene':{'lighting':['sunny'],'subjects':['architecture'],'rating':5},'sha256':{n:sha256(f/n) for n in files}}
  (f/'metadata.json').write_text(json.dumps(meta));return f
 def test_round_trip_and_final_labels_only(self):
  f=self.sample();(f/'history.json').write_text('["not a final label"]');self.sample('G23_002','draft',b'\xff\xd8\xffcapture-b')
  out=self.root/'data.zip';progress=[]
  self.assertEqual(export_dataset(self.samples,out,progress=lambda n,t:progress.append((n,t))),1)
  with zipfile.ZipFile(out) as z:
   self.assertFalse(any('history' in n for n in z.namelist()));z.extractall(self.root/'unpacked')
  root=self.root/'unpacked/G23_Dataset_Export';stats=analyze(root,self.root/'report.html')
  self.assertEqual(stats['exposure_ev']['count'],1);self.assertEqual(progress,[(1,1)])
  lines=(root/'dataset.jsonl').read_text().splitlines();self.assertEqual(len(lines),1);self.assertTrue(json.loads(lines[0])['approved'])
 def test_read_zip_directly_and_reject_path_traversal(self):
  self.sample();archive=self.root/'data.zip';export_dataset(self.samples,archive)
  stats=analyze_zip(archive,self.root/'direct.html');self.assertEqual(stats['exposure_ev']['count'],1)
  bad=self.root/'bad.zip'
  with zipfile.ZipFile(bad,'w') as z:z.writestr('../escape','bad')
  with self.assertRaises(ValueError):analyze_zip(bad,self.root/'bad.html')
  self.assertFalse((self.root.parent/'escape').exists())
 def test_reader_rejects_cross_sample_pairing(self):
  self.sample();self.sample('G23_002',original=b'capture-b')
  archive=self.root/'data.zip';export_dataset(self.samples,archive)
  with zipfile.ZipFile(archive) as z:z.extractall(self.root/'read')
  root=self.root/'read/G23_Dataset_Export';path=root/'dataset.jsonl';rows=[json.loads(line) for line in path.read_text().splitlines()]
  rows[0]['original']=rows[1]['original'];path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
  with self.assertRaisesRegex(ValueError,'paths do not match'):analyze(root,self.root/'report.html')
 def test_checksum_and_missing_file(self):
  f=self.sample();(f/'original.jpg').write_bytes(b'wrong')
  with self.assertRaisesRegex(ValueError,'checksum'):export_dataset(self.samples,self.root/'out.zip')
  (f/'original.jpg').unlink()
  with self.assertRaisesRegex(ValueError,'missing'):export_dataset(self.samples,self.root/'out.zip')
 def test_duplicates(self):
  self.sample();self.sample('G23_002')
  with self.assertRaisesRegex(ValueError,'duplicate original'):export_dataset(self.samples,self.root/'out.zip')
 def test_include_drafts(self):
  self.sample(status='draft')
  with self.assertRaisesRegex(ValueError,'no matching'):export_dataset(self.samples,self.root/'out.zip')
  self.assertEqual(export_dataset(self.samples,self.root/'out.zip',False),1)
 def test_symlink_and_path_escape(self):
  f=self.sample();(f/'edited.png').unlink();(f/'edited.png').symlink_to('/etc/passwd')
  with self.assertRaises(ValueError):export_dataset(self.samples,self.root/'out.zip')
 def test_reject_corrupt_schema(self):
  d=read_json(DEFAULT);del d['parameters']['hsl']['cyan']
  with self.assertRaises(ValueError):validate_parameters(d)
  d=read_json(DEFAULT);d['parameters']['geometry']['crop']=[.8,0,.2,1]
  with self.assertRaises(ValueError):validate_parameters(d)
 def test_preserve_existing_export_on_failure(self):
  self.sample();out=self.root/'out.zip';out.write_bytes(b'previous')
  def fail(n,t):raise RuntimeError('disk disconnected')
  with self.assertRaises(RuntimeError):export_dataset(self.samples,out,progress=fail)
  self.assertEqual(out.read_bytes(),b'previous');self.assertFalse(list(self.root.glob('.g23-*')))
if __name__=='__main__':unittest.main()
