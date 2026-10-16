import { image } from '@kit.ImageKit';
import { Document, Capture, clone } from './Model';
export type ParameterValue = number | number[] | number[][] | null;
export class ParameterPair {
 parameter: string; label: string; before: ParameterValue; after: ParameterValue;
 constructor(path: string,label: string,before: ParameterValue,after: ParameterValue){this.parameter=path;this.label=label;this.before=before;this.after=after;}
}
export function parameterPairs(before: Document,after: Document): ParameterPair[] {
 return [
  new ParameterPair('blacks','黑色色阶',before.parameters.blacks,after.parameters.blacks),
  new ParameterPair('color_grading.balance','色彩分级 · 平衡',before.parameters.color_grading.balance,after.parameters.color_grading.balance),
  new ParameterPair('color_grading.blending','色彩分级 · 混合范围',before.parameters.color_grading.blending,after.parameters.color_grading.blending),
  new ParameterPair('color_grading.highlights.hue','色彩分级 · 高光 · 色相',before.parameters.color_grading.highlights.hue,after.parameters.color_grading.highlights.hue),
  new ParameterPair('color_grading.highlights.luminance','色彩分级 · 高光 · 明度',before.parameters.color_grading.highlights.luminance,after.parameters.color_grading.highlights.luminance),
  new ParameterPair('color_grading.highlights.saturation','色彩分级 · 高光 · 饱和度',before.parameters.color_grading.highlights.saturation,after.parameters.color_grading.highlights.saturation),
  new ParameterPair('color_grading.midtones.hue','色彩分级 · 中间调 · 色相',before.parameters.color_grading.midtones.hue,after.parameters.color_grading.midtones.hue),
  new ParameterPair('color_grading.midtones.luminance','色彩分级 · 中间调 · 明度',before.parameters.color_grading.midtones.luminance,after.parameters.color_grading.midtones.luminance),
  new ParameterPair('color_grading.midtones.saturation','色彩分级 · 中间调 · 饱和度',before.parameters.color_grading.midtones.saturation,after.parameters.color_grading.midtones.saturation),
  new ParameterPair('color_grading.shadows.hue','色彩分级 · 阴影 · 色相',before.parameters.color_grading.shadows.hue,after.parameters.color_grading.shadows.hue),
  new ParameterPair('color_grading.shadows.luminance','色彩分级 · 阴影 · 明度',before.parameters.color_grading.shadows.luminance,after.parameters.color_grading.shadows.luminance),
  new ParameterPair('color_grading.shadows.saturation','色彩分级 · 阴影 · 饱和度',before.parameters.color_grading.shadows.saturation,after.parameters.color_grading.shadows.saturation),
  new ParameterPair('contrast','对比度',before.parameters.contrast,after.parameters.contrast),
  new ParameterPair('exposure_ev','曝光',before.parameters.exposure_ev,after.parameters.exposure_ev),
  new ParameterPair('geometry.crop','构图 · 裁切范围',before.parameters.geometry.crop,after.parameters.geometry.crop),
  new ParameterPair('geometry.rotation_degrees','构图 · 旋转角度',before.parameters.geometry.rotation_degrees,after.parameters.geometry.rotation_degrees),
  new ParameterPair('highlights','高光',before.parameters.highlights,after.parameters.highlights),
  new ParameterPair('hsl.blue.hue','分色 · 蓝色 · 色相',before.parameters.hsl.blue.hue,after.parameters.hsl.blue.hue),
  new ParameterPair('hsl.blue.luminance','分色 · 蓝色 · 明度',before.parameters.hsl.blue.luminance,after.parameters.hsl.blue.luminance),
  new ParameterPair('hsl.blue.saturation','分色 · 蓝色 · 饱和度',before.parameters.hsl.blue.saturation,after.parameters.hsl.blue.saturation),
  new ParameterPair('hsl.cyan.hue','分色 · 青色 · 色相',before.parameters.hsl.cyan.hue,after.parameters.hsl.cyan.hue),
  new ParameterPair('hsl.cyan.luminance','分色 · 青色 · 明度',before.parameters.hsl.cyan.luminance,after.parameters.hsl.cyan.luminance),
  new ParameterPair('hsl.cyan.saturation','分色 · 青色 · 饱和度',before.parameters.hsl.cyan.saturation,after.parameters.hsl.cyan.saturation),
  new ParameterPair('hsl.green.hue','分色 · 绿色 · 色相',before.parameters.hsl.green.hue,after.parameters.hsl.green.hue),
  new ParameterPair('hsl.green.luminance','分色 · 绿色 · 明度',before.parameters.hsl.green.luminance,after.parameters.hsl.green.luminance),
  new ParameterPair('hsl.green.saturation','分色 · 绿色 · 饱和度',before.parameters.hsl.green.saturation,after.parameters.hsl.green.saturation),
  new ParameterPair('hsl.magenta.hue','分色 · 洋红 · 色相',before.parameters.hsl.magenta.hue,after.parameters.hsl.magenta.hue),
  new ParameterPair('hsl.magenta.luminance','分色 · 洋红 · 明度',before.parameters.hsl.magenta.luminance,after.parameters.hsl.magenta.luminance),
  new ParameterPair('hsl.magenta.saturation','分色 · 洋红 · 饱和度',before.parameters.hsl.magenta.saturation,after.parameters.hsl.magenta.saturation),
  new ParameterPair('hsl.orange.hue','分色 · 橙色 · 色相',before.parameters.hsl.orange.hue,after.parameters.hsl.orange.hue),
  new ParameterPair('hsl.orange.luminance','分色 · 橙色 · 明度',before.parameters.hsl.orange.luminance,after.parameters.hsl.orange.luminance),
  new ParameterPair('hsl.orange.saturation','分色 · 橙色 · 饱和度',before.parameters.hsl.orange.saturation,after.parameters.hsl.orange.saturation),
  new ParameterPair('hsl.purple.hue','分色 · 紫色 · 色相',before.parameters.hsl.purple.hue,after.parameters.hsl.purple.hue),
  new ParameterPair('hsl.purple.luminance','分色 · 紫色 · 明度',before.parameters.hsl.purple.luminance,after.parameters.hsl.purple.luminance),
  new ParameterPair('hsl.purple.saturation','分色 · 紫色 · 饱和度',before.parameters.hsl.purple.saturation,after.parameters.hsl.purple.saturation),
  new ParameterPair('hsl.red.hue','分色 · 红色 · 色相',before.parameters.hsl.red.hue,after.parameters.hsl.red.hue),
  new ParameterPair('hsl.red.luminance','分色 · 红色 · 明度',before.parameters.hsl.red.luminance,after.parameters.hsl.red.luminance),
  new ParameterPair('hsl.red.saturation','分色 · 红色 · 饱和度',before.parameters.hsl.red.saturation,after.parameters.hsl.red.saturation),
  new ParameterPair('hsl.yellow.hue','分色 · 黄色 · 色相',before.parameters.hsl.yellow.hue,after.parameters.hsl.yellow.hue),
  new ParameterPair('hsl.yellow.luminance','分色 · 黄色 · 明度',before.parameters.hsl.yellow.luminance,after.parameters.hsl.yellow.luminance),
  new ParameterPair('hsl.yellow.saturation','分色 · 黄色 · 饱和度',before.parameters.hsl.yellow.saturation,after.parameters.hsl.yellow.saturation),
  new ParameterPair('saturation','饱和度',before.parameters.saturation,after.parameters.saturation),
  new ParameterPair('shadows','阴影',before.parameters.shadows,after.parameters.shadows),
  new ParameterPair('temperature_shift','色温偏移',before.parameters.temperature_shift,after.parameters.temperature_shift),
  new ParameterPair('texture.clarity','质感 · 清晰度',before.parameters.texture.clarity,after.parameters.texture.clarity),
  new ParameterPair('texture.fade','质感 · 褪色',before.parameters.texture.fade,after.parameters.texture.fade),
  new ParameterPair('texture.grain_amount','质感 · 颗粒强度',before.parameters.texture.grain_amount,after.parameters.texture.grain_amount),
  new ParameterPair('texture.grain_seed','质感 · 颗粒种子',before.parameters.texture.grain_seed,after.parameters.texture.grain_seed),
  new ParameterPair('texture.grain_size','质感 · 颗粒大小',before.parameters.texture.grain_size,after.parameters.texture.grain_size),
  new ParameterPair('texture.local_contrast','质感 · 局部对比度',before.parameters.texture.local_contrast,after.parameters.texture.local_contrast),
  new ParameterPair('texture.sharpness','质感 · 锐度',before.parameters.texture.sharpness,after.parameters.texture.sharpness),
  new ParameterPair('texture.vignette','质感 · 暗角',before.parameters.texture.vignette,after.parameters.texture.vignette),
  new ParameterPair('tint','色调',before.parameters.tint,after.parameters.tint),
  new ParameterPair('tone_curves.blue','曲线 · 蓝色',before.parameters.tone_curves.blue,after.parameters.tone_curves.blue),
  new ParameterPair('tone_curves.green','曲线 · 绿色',before.parameters.tone_curves.green,after.parameters.tone_curves.green),
  new ParameterPair('tone_curves.red','曲线 · 红色',before.parameters.tone_curves.red,after.parameters.tone_curves.red),
  new ParameterPair('tone_curves.rgb','曲线 · 总曲线',before.parameters.tone_curves.rgb,after.parameters.tone_curves.rgb),
  new ParameterPair('vibrance','自然饱和度',before.parameters.vibrance,after.parameters.vibrance),
  new ParameterPair('whites','白色色阶',before.parameters.whites,after.parameters.whites)
 ];
}
export function parameterName(path: string): string {const row=parameterPairs(new Document(),new Document()).find((p: ParameterPair)=>p.parameter===path);return row?row.label:path;}
export class Statistics {
 width: number = 0; height: number = 0; sample_count: number = 0;
 sampling: string = 'deterministic_grid_max_256x256'; color_space: string = 'sRGB';
 mean_red_0_255: number = 0; mean_green_0_255: number = 0; mean_blue_0_255: number = 0;
 mean_luma_0_255: number = 0; mean_saturation_0_1: number = 0; dark_fraction: number = 0; highlight_fraction: number = 0;
 luma_histogram_64: number[] = new Array<number>(64).fill(0);
 static async compute(pixel: image.PixelMap): Promise<Statistics> {
  const info=await pixel.getImageInfo();const s=new Statistics();s.width=info.size.width;s.height=info.size.height;
  const buffer=new ArrayBuffer(s.width*s.height*4);await pixel.readPixelsToBuffer(buffer);const bytes=new Uint8Array(buffer);
  const sx=Math.max(1,Math.ceil(s.width/256));const sy=Math.max(1,Math.ceil(s.height/256));
  for(let y=0;y<s.height;y+=sy)for(let x=0;x<s.width;x+=sx){const i=(y*s.width+x)*4;const r=bytes[i],g=bytes[i+1],b=bytes[i+2];const l=.2126*r+.7152*g+.0722*b;
   const hi=Math.max(r,g,b)/255,lo=Math.min(r,g,b)/255,delta=hi-lo,light=(hi+lo)/2;
   s.mean_red_0_255+=r;s.mean_green_0_255+=g;s.mean_blue_0_255+=b;s.mean_luma_0_255+=l;s.mean_saturation_0_1+=delta===0?0:delta/(1-Math.abs(2*light-1));
   if(l<=5)s.dark_fraction++;if(l>=250)s.highlight_fraction++;s.luma_histogram_64[Math.min(63,Math.floor(l/4))]++;s.sample_count++;
  }
  s.mean_red_0_255/=s.sample_count;s.mean_green_0_255/=s.sample_count;s.mean_blue_0_255/=s.sample_count;s.mean_luma_0_255/=s.sample_count;s.mean_saturation_0_1/=s.sample_count;s.dark_fraction/=s.sample_count;s.highlight_fraction/=s.sample_count;return s;
 }
}
export class AnalysisImage {
 image: string; parameters: Document; statistics: Statistics;
 constructor(file: string,doc: Document,statistics: Statistics){this.image=file;this.parameters=clone(doc);this.statistics=statistics;}
}
export class Comparison {
 analysis_schema_version: string = '1.0'; sample_id: string; render_pipeline_version: string = '1.0.0';
 comparison_basis: string = 'original decoded with the SAME crop and rotation as edited; source file remains unchanged';
 capture: Capture; before: AnalysisImage; after: AnalysisImage; parameter_pairs: ParameterPair[];
 constructor(id: string,capture: Capture,before: Document,after: Document,original: Statistics,edited: Statistics){this.sample_id=id;this.capture=capture;this.before=new AnalysisImage('original.jpg',before,original);this.after=new AnalysisImage('edited.png',after,edited);this.parameter_pairs=parameterPairs(before,after);}
}

export class DisplayPair {
 label: string; before: string; after: string;
 constructor(label: string,before: string,after: string){this.label=label;this.before=before;this.after=after;}
}
export function capturePairs(c: Capture): DisplayPair[] {
 return [new DisplayPair('设备型号',c.device_model,c.device_model),new DisplayPair('拍摄时间',c.captured_at,c.captured_at),new DisplayPair('感光度',JSON.stringify(c.iso),JSON.stringify(c.iso)),new DisplayPair('快门时间（秒）',JSON.stringify(c.exposure_time_seconds),JSON.stringify(c.exposure_time_seconds)),new DisplayPair('光圈',JSON.stringify(c.aperture),JSON.stringify(c.aperture)),new DisplayPair('焦距（毫米）',JSON.stringify(c.focal_length),JSON.stringify(c.focal_length)),new DisplayPair('白平衡',JSON.stringify(c.white_balance),JSON.stringify(c.white_balance))];
}
export function statisticsPairs(c: Comparison): DisplayPair[] {
 const a=c.before.statistics,b=c.after.statistics;
 return [new DisplayPair('平均红色（0–255）',a.mean_red_0_255.toFixed(2),b.mean_red_0_255.toFixed(2)),new DisplayPair('平均绿色（0–255）',a.mean_green_0_255.toFixed(2),b.mean_green_0_255.toFixed(2)),new DisplayPair('平均蓝色（0–255）',a.mean_blue_0_255.toFixed(2),b.mean_blue_0_255.toFixed(2)),new DisplayPair('平均亮度（0–255）',a.mean_luma_0_255.toFixed(2),b.mean_luma_0_255.toFixed(2)),new DisplayPair('平均饱和度（0–1）',a.mean_saturation_0_1.toFixed(4),b.mean_saturation_0_1.toFixed(4)),new DisplayPair('暗部占比（0–1）',a.dark_fraction.toFixed(4),b.dark_fraction.toFixed(4)),new DisplayPair('高光占比（0–1）',a.highlight_fraction.toFixed(4),b.highlight_fraction.toFixed(4))];
}
