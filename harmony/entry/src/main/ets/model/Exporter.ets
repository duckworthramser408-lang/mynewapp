import { fileIo as fs, fileUri, picker } from '@kit.CoreFileKit';
import { zlib } from '@kit.BasicServicesKit';
import { common, wantConstant } from '@kit.AbilityKit';
import { Document, Metadata } from './Model';
import { Store, Hashes } from './Store';
import { Imaging } from './Imaging';

class Row {
 sample_id: string = '';
 original: string = '';
 edited: string = '';
 parameters: string = '';
 metadata: string = '';
 scene: Object = {};
 device_model: string = '';
 rating: number | null = null;
 approved: boolean = false;
 render_pipeline_version: string = '1.0.0';
}
export class Exporter {
 static async export(store: Store,folders: string[],approvedOnly: boolean,onProgress: (n: number,total: number) => void): Promise<string> {
  const selected=folders.filter((folder: string) => !approvedOnly || store.meta(folder).status==='approved');
  if(selected.length===0) throw new Error('请先选择已保存成片的样本');
  const originals: string[]=[];const ids: string[]=[];
  const stage=store.context.cacheDir+'/g23-export-'+Date.now();fs.mkdirSync(stage);
  const root=stage+'/G23_Dataset_Export';fs.mkdirSync(root);fs.mkdirSync(root+'/samples');fs.mkdirSync(root+'/reports');
  const manifest: string[]=[];const rows: string[]=[];const summaries: string[]=[];
  try {
   for(let i=0;i<selected.length;i++) {
    const folder=selected[i];const meta=store.meta(folder);const doc=store.params(folder);Imaging.validate(doc);
    if(!/^G23_[A-Za-z0-9_-]+$/.test(meta.sample_id) || folder.split('/').pop()!==meta.sample_id || ids.includes(meta.sample_id)) throw new Error('样本 ID 重复或不匹配');
    ids.push(meta.sample_id);
    const hashes=meta.sha256 as Hashes;
    const names=['original.jpg','edited.png','thumbnail_before.jpg','thumbnail_after.jpg','parameters.json'];
    const expected=[hashes['original.jpg'],hashes['edited.png'],hashes['thumbnail_before.jpg'],hashes['thumbnail_after.jpg'],hashes['parameters.json']];
    if(originals.includes(expected[0])) throw new Error('重复原图');originals.push(expected[0]);
    const relative='samples/'+meta.sample_id+'/';fs.mkdirSync(root+'/'+relative);
    for(let j=0;j<names.length;j++) {
     const path=folder+'/'+names[j];if(!fs.accessSync(path) || await Store.hash(path)!==expected[j]) throw new Error('文件缺失或 SHA-256 不匹配：'+names[j]);
     fs.copyFileSync(path,root+'/'+relative+names[j]);
     manifest.push(JSON.stringify(relative+names[j])+':'+JSON.stringify(await Store.hash(root+'/'+relative+names[j])));
    }
    fs.copyFileSync(folder+'/metadata.json',root+'/'+relative+'metadata.json');
    manifest.push(JSON.stringify(relative+'metadata.json')+':'+JSON.stringify(await Store.hash(root+'/'+relative+'metadata.json')));
    const row=new Row();row.sample_id=meta.sample_id;row.original=relative+'original.jpg';row.edited=relative+'edited.png';row.parameters=relative+'parameters.json';row.metadata=relative+'metadata.json';row.scene=meta.scene;row.device_model=meta.capture.device_model;row.rating=meta.scene.rating;row.approved=meta.status==='approved';rows.push(JSON.stringify(row));
    // Full numeric parameter table, curve arrays stay in referenced JSON.
    const values=controlsForCsv(doc);summaries.push([meta.sample_id,...values,relative+'parameters.json'].map((v: string) => '"'+v.replace(/"/g,'""')+'"').join(','));
    onProgress(i+1,selected.length);
   }
   Store.text(root+'/manifest.json','{"schema_version":"1.0","sample_count":'+rows.length+',"sha256":{'+manifest.join(',')+'}}');
   Store.text(root+'/dataset.jsonl',rows.join('\n')+'\n');
   Store.text(root+'/reports/parameters_summary.csv','sample_id,'+csvNames().join(',')+',curves_file\n'+summaries.join('\n')+'\n');
   Store.text(root+'/reports/dataset_summary.json',JSON.stringify({count:rows.length,pipeline:'1.0.0'}));
   Store.text(root+'/README.md','# G23 Dataset\nSDR sRGB. Final labels only. Verify manifest hashes before training.\nOriginal JPEG may contain location EXIF: sharing requires explicit consent.\n');
   const zip=store.context.cacheDir+'/G23_'+Date.now()+'.zip';
   await zlib.zipFile(stage,zip,{level:zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION});return zip;
  } finally {fs.rmdirSync(stage);}
 }
 static async share(context: common.UIAbilityContext,path: string): Promise<void> {
  try {await context.startAbility({action:'ohos.want.action.sendData',type:'application/zip',uri:fileUri.getUriFromPath(path),flags:wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION});}
  catch(e) {
   // If no share target handles ZIP, use the system Documents save picker.
   const options=new picker.DocumentSaveOptions();options.newFileNames=['G23_Dataset.zip'];
   const files=await new picker.DocumentViewPicker(context).save(options);
   if(files.length===0) throw new Error('保存已取消；ZIP 仍在应用缓存中');
   const destination=fs.openSync(files[0],fs.OpenMode.READ_WRITE);try{fs.copyFileSync(path,destination.fd);}finally{fs.closeSync(destination);}
  }
 }
}
import { controls } from './Model';
function controlsForCsv(d: Document): string[] {return controls().map((c) => c.get(d).toString());}
function csvNames(): string[] {return controls().map((c) => c.path);}
