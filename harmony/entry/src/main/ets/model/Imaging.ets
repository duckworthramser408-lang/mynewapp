import { image } from '@kit.ImageKit';
import { colorSpaceManager } from '@kit.ArkGraphics2D';
import { fileIo as fs } from '@kit.CoreFileKit';
import native from 'libg23.so';
import { Document, clone } from './Model';
import { CubeTable } from './LutLibrary';

export class Imaging {
 static async decode(path: string, maxSide: number = 0): Promise<image.PixelMap> {
  const file=fs.openSync(path,fs.OpenMode.READ_ONLY);
  const source=image.createImageSource(file.fd);
  try {
   const info=await source.getImageInfo();
   const s=maxSide>0 ? Math.min(1,maxSide/Math.max(info.size.width,info.size.height)) : 1;
   let orientation='1';
   try {orientation=(await source.getImageProperty(image.PropertyKey.ORIENTATION)).toLowerCase().replace(/\s/g,'');} catch(e) { /* JPEG without EXIF uses top-left. */ }
   const pixel=await source.createPixelMap({editable:true,desiredPixelFormat:image.PixelMapFormat.RGBA_8888,
    desiredSize:{width:Math.max(1,Math.round(info.size.width*s)),height:Math.max(1,Math.round(info.size.height*s))},
    desiredColorSpace:colorSpaceManager.create(colorSpaceManager.ColorSpace.SRGB)});
   // API 12 decoding does not promise EXIF orientation normalization.
   if(orientation==='2' || orientation==='top-right') await pixel.flip(true,false);
   else if(orientation==='3' || orientation==='bottom-right') await pixel.rotate(180);
   else if(orientation==='4' || orientation==='bottom-left') await pixel.flip(false,true);
   else if(orientation==='5' || orientation==='left-top') {await pixel.flip(true,false);await pixel.rotate(270);}
   else if(orientation==='6' || orientation==='right-top') await pixel.rotate(90);
   else if(orientation==='7' || orientation==='right-bottom') {await pixel.flip(true,false);await pixel.rotate(90);}
   else if(orientation==='8' || orientation==='left-bottom') await pixel.rotate(270);
   else if(orientation!=='1' && orientation!=='top-left') {await pixel.release();throw new Error(`无法可靠解析方向：${orientation}`);}
   return pixel;
  } finally {await source.release();fs.closeSync(file);}
 }
 static identity(doc: Document): Document {
  const d=clone(doc);d.parameters.geometry.crop=null;d.parameters.geometry.rotation_degrees=0;return d;
 }
 static validate(doc: Document): void {
  const crop=doc.parameters.geometry.crop;
  if(crop && (crop.length!==4 || crop.some((v: number) => !Number.isFinite(v) || v<0 || v>1) || crop[0]>=crop[2] || crop[1]>=crop[3])) throw new Error('无效裁切坐标');
  if(![0,90,180,270].includes(doc.parameters.geometry.rotation_degrees)) throw new Error('无效旋转角度');
  native.validate(JSON.stringify(Imaging.identity(doc)));
 }
 static async geometry(input: image.PixelMap,doc: Document): Promise<void> {
   let info=await input.getImageInfo();const c=doc.parameters.geometry.crop;
   if(c) {const x=Math.floor(c[0]*info.size.width),y=Math.floor(c[1]*info.size.height);
    await input.crop({x:x,y:y,size:{width:Math.max(1,Math.floor(c[2]*info.size.width)-x),height:Math.max(1,Math.floor(c[3]*info.size.height)-y)}});
   }
   if(doc.parameters.geometry.rotation_degrees!==0) await input.rotate(doc.parameters.geometry.rotation_degrees);
 }
 static async render(path: string,doc: Document,maxSide: number=0, lut?: CubeTable): Promise<image.PixelMap> {
  Imaging.validate(doc);
  const input=await Imaging.decode(path,maxSide);
  let buffer: ArrayBuffer;
  let width=0;let height=0;
  try {
   await Imaging.geometry(input,doc);
   const info=await input.getImageInfo();width=info.size.width;height=info.size.height;
   if(width*height*16+64*1024*1024>512*1024*1024) throw new Error('图像超过本版本的内存预算，保留原图；请使用较低拍摄分辨率或在桌面处理。');
   buffer=new ArrayBuffer(width*height*4);await input.readPixelsToBuffer(buffer);
  } finally {await input.release();}
  const output=lut?await native.render(buffer,width,height,JSON.stringify(Imaging.identity(doc)),lut.values.buffer,lut.size,lut.minimum.buffer,lut.maximum.buffer):await native.render(buffer,width,height,JSON.stringify(Imaging.identity(doc)));
  const result=await image.createPixelMap(output,{size:{width:width,height:height},pixelFormat:image.PixelMapFormat.RGBA_8888,editable:true,alphaType:image.AlphaType.OPAQUE});
  result.setColorSpace(colorSpaceManager.create(colorSpaceManager.ColorSpace.SRGB));return result;
 }
 static async cropRatio(pixel: image.PixelMap,ratio: string): Promise<void> {
  if(ratio==='legacy')return;
  const info=await pixel.getImageInfo();const width=info.size.width;const height=info.size.height;
  const landscape=ratio==='1:1'?1:ratio==='3:2'?1.5:4/3;const desired=width<height?1/landscape:landscape;
  const w=Math.max(1,Math.min(width,Math.floor(height*desired))),h=Math.max(1,Math.min(height,Math.floor(width/desired)));
  if(width-w>1||height-h>1)await pixel.crop({x:Math.floor((width-w)/2),y:Math.floor((height-h)/2),size:{width:w,height:h}});
 }
 static async develop(path: string,lut: CubeTable,ratio: string): Promise<image.PixelMap> {
  const pixel=await Imaging.decode(path);let buffer: ArrayBuffer;let width=0,height=0;
  try {
   await Imaging.cropRatio(pixel,ratio);const info=await pixel.getImageInfo();width=info.size.width;height=info.size.height;
   if(width*height*16+64*1024*1024>512*1024*1024)throw new Error('可用内存不足，原片已保留');
   buffer=new ArrayBuffer(width*height*4);await pixel.readPixelsToBuffer(buffer);
  } finally {await pixel.release();}
  const output=await native.develop(buffer,width,height,'',lut.values.buffer,lut.size,lut.minimum.buffer,lut.maximum.buffer);
  const result=await image.createPixelMap(output,{size:{width:width,height:height},pixelFormat:image.PixelMapFormat.RGBA_8888,editable:true,alphaType:image.AlphaType.OPAQUE});
  result.setColorSpace(colorSpaceManager.create(colorSpaceManager.ColorSpace.SRGB));return result;
 }
 static async encode(pixel: image.PixelMap,path: string,format: string='image/png'): Promise<void> {
  const packer=image.createImagePacker();const file=fs.openSync(path,fs.OpenMode.CREATE|fs.OpenMode.READ_WRITE|fs.OpenMode.TRUNC);
  try {await packer.packToFile(pixel,file.fd,{format:format,quality:format==='image/png'?100:95});fs.fsyncSync(file.fd);}finally{fs.closeSync(file);await packer.release();}
 }
 static async thumbnail(path: string,target: string): Promise<void> {
  const pixel=await Imaging.decode(path,320);try{await Imaging.encode(pixel,target,'image/jpeg');}finally{await pixel.release();}
 }
}
