import { fileIo as fs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import device from '@ohos.deviceInfo';
import { image } from '@kit.ImageKit';
import { Metadata, Document, Capture, FileHash } from './Model';
import { Imaging } from './Imaging';
import { Comparison, Statistics } from './Analysis';
import { LutLibrary } from './LutLibrary';

export type Hashes = Record<string, string>;
export class Store {
 root: string;
 context: common.UIAbilityContext;
 looks: LutLibrary;
 constructor(context: common.UIAbilityContext){this.context=context;this.looks=new LutLibrary(context);this.root=context.filesDir+'/samples';fs.mkdirSync(this.root,true);fs.mkdirSync(context.filesDir+'/presets',true);}
 static text(path: string,text: string): void {
  const tmp=path+'.tmp';const file=fs.openSync(tmp,fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE|fs.OpenMode.TRUNC);
  try{fs.writeSync(file.fd,text);fs.fsyncSync(file.fd);}finally{fs.closeSync(file);}
  fs.renameSync(tmp,path);
 }
 static read(path: string): string {return fs.readTextSync(path);}
 static async hash(path: string): Promise<string> {
  const md=cryptoFramework.createMd('SHA256');const f=fs.openSync(path,fs.OpenMode.READ_ONLY);
  try{const buffer=new ArrayBuffer(1024*1024);let n=0;while((n=fs.readSync(f.fd,buffer))>0)await md.update({data:new Uint8Array(buffer.slice(0,n))});
   const result=await md.digest();return Array.from(result.data).map((v: number) => v.toString(16).padStart(2,'0')).join('');
  }finally{fs.closeSync(f);}
 }
 async capture(buffer: ArrayBuffer,cameraId: string): Promise<string> {
  const sid='G23_'+util.generateRandomUUID(true).replace(/-/g,'');const folder=this.root+'/'+sid;fs.mkdirSync(folder);
  const file=fs.openSync(folder+'/original.jpg',fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE);
  try{fs.writeSync(file.fd,buffer);fs.fsyncSync(file.fd);}finally{fs.closeSync(file);}
  const meta=new Metadata();meta.sample_id=sid;meta.capture.camera_id=cameraId;
  meta.capture.device_brand=device.brand;meta.capture.device_model=device.productModel;meta.capture.os_version=device.displayVersion;
  meta.capture.captured_at=new Date().toISOString();
  const original=fs.openSync(folder+'/original.jpg',fs.OpenMode.READ_ONLY);const source=image.createImageSource(original.fd);
  try {
   const info=await source.getImageInfo();meta.capture.width=info.size.width;meta.capture.height=info.size.height;
   meta.capture.iso=this.numeric(await this.exif(source,image.PropertyKey.PHOTOGRAPHIC_SENSITIVITY));
   meta.capture.exposure_time_seconds=this.numeric(await this.exif(source,image.PropertyKey.EXPOSURE_TIME));
   meta.capture.aperture=this.numeric(await this.exif(source,image.PropertyKey.F_NUMBER));
   meta.capture.focal_length=this.numeric(await this.exif(source,image.PropertyKey.FOCAL_LENGTH));
   meta.capture.equivalent_focal_length_35mm=this.numeric(await this.exif(source,image.PropertyKey.FOCAL_LENGTH_IN_35_MM_FILM));
   meta.capture.white_balance=await this.exif(source,image.PropertyKey.WHITE_BALANCE);
  } finally {await source.release();fs.closeSync(original);}
  Store.text(folder+'/parameters.json',JSON.stringify(new Document()));this.originalParams(folder);Store.text(folder+'/metadata.json',JSON.stringify(meta));return folder;
 }
 private numeric(raw: string | null): number | null {
  if(raw===null)return null;
  const cleaned=raw.toLowerCase().replace(/^f\//,'').replace(/\s*(sec\.?|mm)$/,'').trim();
  const parts=cleaned.split('/');
  if(parts.length>2 || parts.some((s:string)=>!/^\d+(\.\d+)?$/.test(s.trim())))return null;
  const v=parts.length===2?Number(parts[0])/Number(parts[1]):Number(parts[0]);
  return Number.isFinite(v)&&v>0?v:null;
 }
 private async exif(source: image.ImageSource,key: string): Promise<string | null> {try{return await source.getImageProperty(key);}catch(e){return null;}}
 all(): string[] {return fs.listFileSync(this.root).filter((s: string) => s.startsWith('G23_') && fs.accessSync(this.root+'/'+s+'/metadata.json')).map((s: string) => this.root+'/'+s).sort().reverse();}
 meta(folder: string): Metadata {return JSON.parse(Store.read(folder+'/metadata.json')) as Metadata;}
 params(folder: string): Document {return JSON.parse(Store.read(folder+'/parameters.json')) as Document;}
 originalParams(folder: string): Document {const path=folder+'/original_parameters.json';if(!fs.accessSync(path))Store.text(path,JSON.stringify(new Document()));return JSON.parse(Store.read(path)) as Document;}
 draft(folder: string,doc: Document,history: string): void {
  const m=this.meta(folder);m.status='draft';Store.text(folder+'/metadata.json',JSON.stringify(m));
  Store.text(folder+'/parameters.json',JSON.stringify(doc));Store.text(folder+'/history.json',history);
 }
 async save(folder: string,doc: Document,approved: boolean): Promise<void> {
  const initial=this.meta(folder);initial.status='draft';Store.text(folder+'/metadata.json',JSON.stringify(initial));
  Store.text(folder+'/parameters.json',JSON.stringify(doc));
  const pixel=await Imaging.render(folder+'/original.jpg',doc,0,await this.looks.forPhoto(folder));
  let after=new Statistics();
  try {await Imaging.encode(pixel,folder+'/edited.png.tmp');fs.renameSync(folder+'/edited.png.tmp',folder+'/edited.png');after=await Statistics.compute(pixel);}finally{await pixel.release();}
  const source=await Imaging.decode(folder+'/original.jpg');let before=new Statistics();
  try{await Imaging.geometry(source,doc);before=await Statistics.compute(source);const info=await source.getImageInfo();await source.scale(Math.min(1,320/Math.max(info.size.width,info.size.height)),Math.min(1,320/Math.max(info.size.width,info.size.height)));await Imaging.encode(source,folder+'/thumbnail_before.jpg','image/jpeg');}finally{await source.release();}
  await Imaging.thumbnail(folder+'/edited.png',folder+'/thumbnail_after.jpg');
  const original=this.originalParams(folder);const comparison=new Comparison(initial.sample_id,initial.capture,original,doc,before,after);
  comparison.look=this.looks.look(folder);Store.text(folder+'/comparison.json',JSON.stringify(comparison));
  const hashes: Hashes={};
  for(const name of ['original.jpg','edited.png','thumbnail_before.jpg','thumbnail_after.jpg','parameters.json','original_parameters.json','comparison.json',...(this.looks.look(folder)?['look.json','look.cube']:[])])hashes[name]=await Store.hash(folder+'/'+name);
  const meta=this.meta(folder);const look=this.looks.look(folder);if(look)meta.style=look.name;meta.sha256=hashes;meta.status=approved?'approved':'draft';Store.text(folder+'/metadata.json',JSON.stringify(meta));
 }
 async preset(name: string,doc: Document): Promise<void> {if(!name.trim() || name.length>80)throw new Error('名称需为 1–80 字符');Imaging.validate(doc);const path=this.context.filesDir+'/presets/'+encodeURIComponent(name)+'.json';Store.text(path,JSON.stringify(doc));}
}
