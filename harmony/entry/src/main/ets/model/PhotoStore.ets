import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { LutLibrary, FilmLut } from './LutLibrary';
import { Imaging } from './Imaging';

export class CameraPhoto {
 folder: string = ''; state: string = ''; ratio: string = '4:3'; created: number = 0;
 original(): string {return this.folder+'/original.jpg';}
 film(): string {return fs.accessSync(this.folder+'/film.jpg')?this.folder+'/film.jpg':fs.accessSync(this.folder+'/edited.png')?this.folder+'/edited.png':'';}
 thumbnail(): string {return fs.accessSync(this.folder+'/thumbnail_after.jpg')?this.folder+'/thumbnail_after.jpg':this.film()||this.original();}
 developing(): boolean {return this.state==='queued'||this.state==='developing';}
}
class Recipe {
 version: number = 1; lut: string = ''; ratio: string = '4:3'; created: number = Date.now(); state: string = 'capturing';
}
export class PhotoStore {
 private static instance?: PhotoStore;
 readonly root: string;
 readonly looks: LutLibrary;
 private context: common.UIAbilityContext;
 private processing: boolean = false;
 private activeCaptures: Set<string> = new Set<string>();
 static get(context: common.UIAbilityContext): PhotoStore {if(!PhotoStore.instance)PhotoStore.instance=new PhotoStore(context);else PhotoStore.instance.context=context;return PhotoStore.instance;}
 private constructor(context: common.UIAbilityContext) {this.context=context;this.root=context.filesDir+'/samples';fs.mkdirSync(this.root,true);this.looks=new LutLibrary(context);}
 static text(path: string,text: string): void {
  const tmp=path+'.tmp';const file=fs.openSync(tmp,fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE|fs.OpenMode.TRUNC);
  try{fs.writeSync(file.fd,text);fs.fsyncSync(file.fd);}finally{fs.closeSync(file);}fs.renameSync(tmp,path);
 }
 private notify(): void {this.context.eventHub.emit('g23.photos');}
 photos(): CameraPhoto[] {
  const rows: CameraPhoto[]=[];
  for(const name of fs.listFileSync(this.root)) {
   if(!name.startsWith('G23_'))continue;
   const folder=this.root+'/'+name;if(!fs.statSync(folder).isDirectory()||!fs.accessSync(folder+'/original.jpg'))continue;
   try {
    const photo=new CameraPhoto();photo.folder=folder;
    if(fs.accessSync(folder+'/photo.json')){const record=JSON.parse(fs.readTextSync(folder+'/photo.json')) as Recipe;if(record.state==='capturing')continue;photo.state=record.state;photo.ratio=record.ratio;photo.created=record.created;}
    else{photo.state=fs.accessSync(folder+'/edited.png')?'ready':'original';photo.ratio='legacy';photo.created=fs.statSync(folder).mtime*1000;}
    rows.push(photo);
   }catch(e){console.error('照片暂时不可读取');}
  }
  return rows.sort((a:CameraPhoto,b:CameraPhoto)=>b.created-a.created);
 }
 async capture(bytes: ArrayBuffer,look: FilmLut,ratio: string): Promise<string> {
  const folder=this.root+'/G23_'+util.generateRandomUUID(true).replace(/-/g,'');fs.mkdirSync(folder);
  const record=new Recipe();record.lut=look.id;record.ratio=ratio;PhotoStore.text(folder+'/photo.json',JSON.stringify(record));
  this.activeCaptures.add(folder);
  try {
  const file=await fs.open(folder+'/original.jpg',fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE);
  try{let written=0;while(written<bytes.byteLength){const n=await fs.write(file.fd,written===0?bytes:bytes.slice(written));if(n<=0)throw new Error('照片写入未完成');written+=n;}await fs.fsync(file.fd);}finally{await fs.close(file);}
  record.state='queued';PhotoStore.text(folder+'/photo.json',JSON.stringify(record));this.notify();this.start();return folder;
  }catch(e){if(fs.accessSync(folder)){if(fs.accessSync(folder+'/original.jpg')&&fs.statSync(folder+'/original.jpg').size>0){record.state='failed';PhotoStore.text(folder+'/photo.json',JSON.stringify(record));}else fs.rmdirSync(folder);}this.notify();throw e;}
  finally{this.activeCaptures.delete(folder);}
 }
 recover(): void {
  if(this.processing)return;
  for(const name of fs.listFileSync(this.root)) {
   const folder=this.root+'/'+name;
   if(!fs.accessSync(folder+'/photo.json'))continue;
   try {const r=JSON.parse(fs.readTextSync(folder+'/photo.json')) as Recipe;
    if(r.state==='capturing'){
     if(this.activeCaptures.has(folder))continue;
     if(!fs.accessSync(folder+'/original.jpg')||fs.statSync(folder+'/original.jpg').size===0){fs.rmdirSync(folder);continue;}
     r.state='queued';PhotoStore.text(folder+'/photo.json',JSON.stringify(r));
    }
   }catch(e){console.error('冲洗任务恢复失败');}
  }
  this.start();
 }
 private start(): void {this.pump().catch((e:Error)=>console.error(e.message));}
 private async pump(): Promise<void> {
  if(this.processing)return;this.processing=true;
  try {while(true){
   const photo=this.photos().filter((v:CameraPhoto)=>v.developing()).sort((a:CameraPhoto,b:CameraPhoto)=>a.created-b.created)[0];if(!photo)break;
   const folder=photo.folder;const record=JSON.parse(fs.readTextSync(folder+'/photo.json')) as Recipe;
   record.state='developing';PhotoStore.text(folder+'/photo.json',JSON.stringify(record));this.notify();
   try {
    const table=await this.looks.table(record.lut);
    if(!fs.accessSync(folder))continue;
    const pixel=await Imaging.develop(photo.original(),table,record.ratio);
    try {
     if(!fs.accessSync(folder))continue;
     await Imaging.encode(pixel,folder+'/film.jpg.tmp','image/jpeg');
     const info=await pixel.getImageInfo();const scale=Math.min(1,320/Math.max(info.size.width,info.size.height));await pixel.scale(scale,scale);
     if(!fs.accessSync(folder))continue;
     await Imaging.encode(pixel,folder+'/thumbnail_after.jpg.tmp','image/jpeg');
    }finally{await pixel.release();}
    if(!fs.accessSync(folder))continue;
    fs.renameSync(folder+'/film.jpg.tmp',folder+'/film.jpg');fs.renameSync(folder+'/thumbnail_after.jpg.tmp',folder+'/thumbnail_after.jpg');
    record.state='ready';PhotoStore.text(folder+'/photo.json',JSON.stringify(record));
   }catch(e){if(fs.accessSync(folder)){record.state='failed';PhotoStore.text(folder+'/photo.json',JSON.stringify(record));}console.error((e as Error).message);}
   this.notify();
  }}finally{this.processing=false;this.notify();}
 }
 retry(folder: string): void {
  const r=JSON.parse(fs.readTextSync(folder+'/photo.json')) as Recipe;if(r.state!=='failed')return;
  r.state='queued';PhotoStore.text(folder+'/photo.json',JSON.stringify(r));this.notify();this.start();
 }
 delete(folder: string): void {
  if(!folder.startsWith(this.root+'/G23_')||folder.includes('/../'))throw new Error('无效照片');
  if(!fs.accessSync(folder))return;
  const trash=this.context.filesDir+'/trash_'+util.generateRandomUUID(true);fs.renameSync(folder,trash);this.notify();fs.rmdirSync(trash);
 }
}
