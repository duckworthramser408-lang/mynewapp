import json
from pathlib import Path
import re
import unittest
ROOT=Path(__file__).resolve().parents[1]
class ProtocolTests(unittest.TestCase):
 def test_harmony_defaults_equal_shared_document(self):
  code=(ROOT/'harmony/entry/src/main/ets/model/Model.ets').read_text()
  def instance(name):
   body=re.search(r'export class '+re.escape(name)+r' \{\n(.*?)\n\}',code,re.S).group(1)
   result={}
   for line in body.splitlines():
    match=re.match(r'\s*(\w+): [^=]+ = (.*);',line)
    if not match:continue
    key,value=match.groups();nested=re.fullmatch(r'new (\w+)\(\)',value)
    result[key]=instance(nested.group(1)) if nested else json.loads(value)
   return result
  expected=json.loads((ROOT/'protocol/default_parameters.json').read_text())
  self.assertEqual(instance('Document'),expected)
 def test_harmony_controls_cover_all_numeric_edit_parameters(self):
  from tools.g23_dataset import flatten
  default=json.loads((ROOT/'protocol/default_parameters.json').read_text())
  expected=set(flatten(default['parameters']))-{'geometry.rotation_degrees'}
  code=(ROOT/'harmony/entry/src/main/ets/model/Model.ets').read_text()
  self.assertEqual(set(re.findall(r"new Control\('([^']+)'",code)),expected)
 def test_harmony_comparison_covers_arrays_nulls_and_all_parameters(self):
  from tools.g23_dataset import parameter_values
  default=json.loads((ROOT/'protocol/default_parameters.json').read_text())
  code=(ROOT/'harmony/entry/src/main/ets/model/Analysis.ets').read_text()
  fields=re.findall(r"new ParameterPair\('([^']+)'",code)
  self.assertEqual(len(fields),len(set(fields)))
  self.assertEqual(set(fields),set(parameter_values(default['parameters'])))
