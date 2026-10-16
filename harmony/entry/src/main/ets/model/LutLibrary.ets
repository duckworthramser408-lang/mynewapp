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
export class CubeTable {
 size: number = 0; minimum: Float32Array = new Float32Array([0,0,0]); maximum: Float32Array = new Float32Array([1,1,1]); values: Float32Array = new Float32Array(0);
 static parse(text: string): CubeTable {
  if(text.length>8*1024*1024)throw new Error('LUT 文件过大');
  const cube=new CubeTable();let count=0;const seen: string[]=[];
  for(const raw of text.replace(/^\uFEFF/,'').split(/\r?\n/)){
   const line=raw.split('#')[0].trim();if(!line.length)continue;const parts=line.split(/\s+/);const key=parts[0];
   if(key==='TITLE')continue;
   if(key==='LUT_3D_SIZE'){
    if(seen.includes(key)||count!==0||parts.length!==2)throw new Error('LUT 网格声明无效');seen.push(key);cube.size=Number(parts[1]);
    if(!Number.isInteger(cube.size)||cube.size<2||cube.size>65)throw new Error('LUT 网格不受支持');cube.values=new Float32Array(cube.size**3*3);
   }else if(key==='DOMAIN_MIN'||key==='DOMAIN_MAX'){
    if(seen.includes(key)||count!==0||parts.length!==4)throw new Error('LUT 输入范围无效');seen.push(key);const values=new Float32Array(parts.slice(1).map((v:string)=>Number(v)));if(values.some((v:number)=>!Number.isFinite(v)))throw new Error('LUT 输入范围无效');if(key==='DOMAIN_MIN')cube.minimum=values;else cube.maximum=values;
   }else {
    if(parts.length!==3||cube.size===0||count+3>cube.values.length)throw new Error('LUT 数据不完整');
    for(const part of parts){const value=Number(part);if(!Number.isFinite(value)||Math.abs(value)>64)throw new Error('LUT 包含无效数值');cube.values[count++]=value;}
   }
  }
  if(cube.size===0||count!==cube.values.length||cube.minimum.some((v:number,i:number)=>v>=cube.maximum[i]))throw new Error('LUT 数据不完整');return cube;
 }
}
export class LutLibrary {
 items: FilmLut[]; private context: common.UIAbilityContext;
 constructor(context: common.UIAbilityContext){this.context=context;const text=util.TextDecoder.create('utf-8').decodeToString(context.resourceManager.getRawFileContentSync('luts/catalog.json'));this.items=(JSON.parse(text) as Catalog).luts;if(!this.items.length)throw new Error('缺少胶卷色调');}
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
