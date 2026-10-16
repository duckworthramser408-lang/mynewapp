import { taskpool } from '@kit.ArkTS';
import { CubeTable } from './CubeTable';
export { CubeTable } from './CubeTable';
import { parseCubeTask } from './CubeParseTask';
import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { preferences } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { Store } from './Store';

export class FilmLut {
 id: string = ''; name: string = ''; file: string = ''; sha256: string = ''; color: string = '#B8C7A8'; grid_size: number = 33;
}
class Catalog {luts: FilmLut[] = [];}
export class Look {
 look_pipeline_version: string = 'cube_srgb_trilinear_1.0'; id: string = ''; name: string = ''; source_name: string = '';
 sha256: string = ''; file: string = 'look.cube'; grid_size: number = 33;
 input_color_space: string = 'sRGB'; intensity: number = 1; applied_after: string = 'g23_parameters_1.0.0';
}
export class LutLibrary {
 items: FilmLut[]; private context: common.UIAbilityContext;
 constructor(context: common.UIAbilityContext){this.context=context;const text=util.TextDecoder.create('utf-8').decodeToString(context.resourceManager.getRawFileContentSync('luts/catalog.json'));this.items=(JSON.parse(text) as Catalog).luts;if(!this.items.length)throw new Error('缺少胶卷色调');}
 private cache: Map<string,CubeTable> = new Map<string,CubeTable>();
 async table(id: string): Promise<CubeTable> {
  const hit=this.cache.get(id);if(hit)return hit;
  const item=this.items.find((v:FilmLut)=>v.id===id);if(!item)throw new Error('找不到这款胶卷');
  const data=this.context.resourceManager.getRawFileContentSync('luts/'+item.file);
  const text=util.TextDecoder.create('utf-8').decodeToString(data);
  const packed=new Float32Array(await taskpool.execute(new taskpool.Task(parseCubeTask,text)) as ArrayBuffer);
  const table=new CubeTable();table.size=packed[0];table.minimum=packed.slice(1,4);table.maximum=packed.slice(4,7);table.values=packed.slice(7);
  if(this.cache.size>=3)this.cache.clear();
  this.cache.set(id,table);return table;
 }
 selected(): FilmLut {const p=preferences.getPreferencesSync(this.context,{name:'camera'});const id=p.getSync('lut',this.items[0].id) as string;return this.items.find((v:FilmLut)=>v.id===id)||this.items[0];}
 async select(item: FilmLut): Promise<void> {const p=preferences.getPreferencesSync(this.context,{name:'camera'});p.putSync('lut',item.id);await p.flush();}
 async attach(folder: string,item: FilmLut): Promise<void> {
  if(fs.accessSync(folder+'/look.json')||fs.accessSync(folder+'/look.cube'))throw new Error('照片已绑定胶卷色调');
  const data=this.context.resourceManager.getRawFileContentSync('luts/'+item.file);const tmp=folder+'/look.cube.tmp';const file=fs.openSync(tmp,fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE|fs.OpenMode.TRUNC);
  try{fs.writeSync(file.fd,data.buffer.slice(data.byteOffset,data.byteOffset+data.byteLength));fs.fsyncSync(file.fd);}finally{fs.closeSync(file);}
  if(await Store.hash(tmp)!==item.sha256)throw new Error('LUT 校验失败');fs.renameSync(tmp,folder+'/look.cube');
  const look=new Look();look.id=item.id;look.name=item.name;look.source_name=item.name+'.cube';look.sha256=item.sha256;look.grid_size=item.grid_size;Store.text(folder+'/look.json',JSON.stringify(look));
 }
 look(folder: string): Look | null {if(!fs.accessSync(folder+'/look.json')&&!fs.accessSync(folder+'/look.cube'))return null;if(!fs.accessSync(folder+'/look.json')||!fs.accessSync(folder+'/look.cube'))throw new Error('照片的 LUT 记录不完整');const look=JSON.parse(Store.read(folder+'/look.json')) as Look;if(look.look_pipeline_version!=='cube_srgb_trilinear_1.0'||look.file!=='look.cube'||look.input_color_space!=='sRGB'||look.applied_after!=='g23_parameters_1.0.0'||look.intensity!==1)throw new Error('LUT 版本不受支持');return look;}
 async forPhoto(folder: string): Promise<CubeTable | undefined> {const look=this.look(folder);if(!look)return undefined;const path=folder+'/look.cube';if(await Store.hash(path)!==look.sha256)throw new Error('照片绑定的 LUT 已损坏');const table=CubeTable.parse(Store.read(path));if(table.size!==look.grid_size)throw new Error('LUT 网格大小不符');return table;}
}
