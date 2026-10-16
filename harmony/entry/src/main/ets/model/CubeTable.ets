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
