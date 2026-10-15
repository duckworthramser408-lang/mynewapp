import { CurveEditor } from './CurveEditor';
import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { fileIo as fs, picker } from '@kit.CoreFileKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { CameraService } from '../model/CameraService';
import { Store } from '../model/Store';
import { Imaging } from '../model/Imaging';
import { Exporter } from '../model/Exporter';
import { Document, Metadata, Control, controls, clone, HistoryState } from '../model/Model';

@Entry
@Component
struct Index {
 @State page: string = 'Camera';
 @State status: string = '连接相机后显示设备能力';
 @State ready: boolean = false;
 @State busy: boolean = false;
 @State front: boolean = false;
 @State flash: boolean = false;
 @State grid: boolean = true;
 @State ratio: string = '4:3';
 @State ev: number = 0;
 @State doc: Document = new Document();
 @State category: string = '基础';
 @State before?: image.PixelMap = undefined;
 @State after?: image.PixelMap = undefined;
 @State showOriginal: boolean = false;
 @State compare: boolean = false;
 @State split: number = 50;
 @State zoom: number = 1;
 @State panX: number = 0;
 @State panY: number = 0;
 @State panel: boolean = false;
 @State samples: string[] = [];
 @State selected: string[] = [];
 @State samplePage: number = 0;
 @State filter: string = '';
 @State filterDevice: string = '';
 @State filterDate: string = '';
 @State filterStatus: string = 'all';
 @State approvedOnly: boolean = true;
 @State sceneLighting: string = '';
 @State sceneSubjects: string = '';
 @State notes: string = '';
 @State styleFeatures: string = '';
 @State rating: number = 0;
 @State satisfied: boolean = false;
 @State presetName: string = '';
 @State presets: string[] = [];
 @State curveChannel: string = 'rgb';
 @State curveText: string = '[[0,0],[1,1]]';
 @State cropText: string = '[0,0,1,1]';
 private camera: CameraService = new CameraService();
 private surface: XComponentController = new XComponentController();
 private store?: Store;
 private folder: string = '';
 private history: string[] = [];
 private cursor: number = 0;
 private renderToken: number = 0;
 private rendering: boolean = false;
 private pending: boolean = false;
 private surfaceId: string = '';
 private zoomBase: number = 1;
 private baseX: number = 0;
 private baseY: number = 0;
 private previewWidth: number = 1;
 private previewHeight: number = 1;
 private context(): common.UIAbilityContext {return getContext(this) as common.UIAbilityContext;}
 private backgroundCallback: () => void = (): void => {this.ready=false;this.camera.close().catch((e:Error)=>this.status=e.message);};
 private foregroundCallback: () => void = (): void => {if(this.page==='Camera' && this.surfaceId.length>0)this.openCamera();};
 aboutToAppear(): void {this.context().eventHub.on('g23.background',this.backgroundCallback);this.context().eventHub.on('g23.foreground',this.foregroundCallback);this.store=new Store(this.context());this.refresh();}
 aboutToDisappear(): void {this.context().eventHub.off('g23.background',this.backgroundCallback);this.context().eventHub.off('g23.foreground',this.foregroundCallback);this.renderToken++;this.camera.close().catch((e: Error) => console.error(e.message));}
 private async run(job: () => Promise<void>): Promise<void> {
  if(this.busy)return;this.busy=true;
  try{await job();}catch(e){this.status=(e as Error).message;AlertDialog.show({message:this.status,confirm:{value:'确定',action:():void=>{}}});}
  finally{this.busy=false;}
 }
 private async openCamera(): Promise<void> {
  this.ready=false;
  try{await this.camera.open(this.context(),this.surfaceId,this.front);this.ready=true;this.status=this.camera.capability;}
  catch(e){this.status=(e as Error).message;}
 }
 private async navigate(page: string): Promise<void> {
  this.renderToken++;await this.camera.close();this.ready=false;this.page=page;
  if(page==='Dataset')this.refresh();
 }
 private async shoot(): Promise<void> {
  const bytes=await this.camera.capture();this.folder=await this.store!.capture(bytes,this.camera.cameraId);
  const d=this.store!.params(this.folder);const source=await Imaging.decode(this.folder+'/original.jpg',640);
  try{const info=await source.getImageInfo();const landscape=this.ratio==='3:2'?1.5:this.ratio==='1:1'?1:4/3;const desired=info.size.width<info.size.height?1/landscape:landscape;const actual=info.size.width/info.size.height;
   d.parameters.geometry.crop=actual>desired?[(1-desired/actual)/2,0,(1+desired/actual)/2,1]:[0,(1-actual/desired)/2,1,(1+actual/desired)/2];
  }finally{await source.release();}
  Store.text(this.folder+'/parameters.json',JSON.stringify(d));await this.edit(this.folder);
 }
 private async edit(folder: string): Promise<void> {
  this.renderToken++;this.pending=false;
  const previousAfter=this.after;this.after=undefined;await previousAfter?.release();
  await this.camera.close();this.ready=false;this.folder=folder;this.doc=this.store!.params(folder);
  this.history=[JSON.stringify(this.doc)];this.cursor=0;
  if(fs.accessSync(folder+'/history.json')){try{const saved=JSON.parse(Store.read(folder+'/history.json')) as HistoryState;if(saved.cursor>=0&&saved.cursor<saved.states.length&&saved.states[saved.cursor]===JSON.stringify(this.doc)){this.history=saved.states;this.cursor=saved.cursor;}}catch(e){this.status='历史文件不可读；已恢复最终参数快照';}}
  this.page='Editor';
  const meta=this.store!.meta(folder);this.sceneLighting=meta.scene.lighting.join(',');this.sceneSubjects=meta.scene.subjects.join(',');this.rating=meta.scene.rating||0;this.notes=meta.scene.notes;this.styleFeatures=meta.scene.style_features;this.satisfied=meta.scene.satisfied||false;
  const old=this.before;this.before=await Imaging.decode(folder+'/original.jpg',1200);await old?.release();
  this.zoom=1;this.panX=0;this.panY=0;this.preview();
 }
 private changed(record: boolean=true): void {
  if(record){this.history=this.history.slice(0,this.cursor+1);this.history.push(JSON.stringify(this.doc));this.cursor=this.history.length-1;}
  const saved=new HistoryState();saved.states=this.history;saved.cursor=this.cursor;this.store!.draft(this.folder,this.doc,JSON.stringify(saved));this.doc=clone(this.doc);this.preview();
 }
 private async preview(): Promise<void> {
  this.renderToken++;this.pending=true;if(this.rendering)return;
  this.rendering=true;
  try{while(this.pending && this.page==='Editor'){
   this.pending=false;const token=this.renderToken;const snapshot=clone(this.doc);
   try{const output=await Imaging.render(this.folder+'/original.jpg',snapshot,640);
    if(token===this.renderToken && this.page==='Editor'){
     const comparison=await Imaging.decode(this.folder+'/original.jpg',640);
     try{await Imaging.geometry(comparison,snapshot);}catch(e){await comparison.release();await output.release();throw e;}
     if(token===this.renderToken && this.page==='Editor'){const old=this.after;const oldBefore=this.before;this.before=comparison;this.after=output;await old?.release();await oldBefore?.release();}
     else{await comparison.release();await output.release();}
    }else await output.release();
   }catch(e){this.status=(e as Error).message;}
  }}finally{this.rendering=false;}
 }
 private saveScene(): void {
  const meta=this.store!.meta(this.folder);meta.scene.lighting=this.sceneLighting.split(',').map((s: string)=>s.trim()).filter((s: string)=>s.length>0);meta.scene.subjects=this.sceneSubjects.split(',').map((s: string)=>s.trim()).filter((s: string)=>s.length>0);meta.scene.notes=this.notes;meta.scene.style_features=this.styleFeatures;meta.scene.rating=this.rating>0?this.rating:null;meta.scene.satisfied=this.satisfied;
  Store.text(this.folder+'/metadata.json',JSON.stringify(meta));
 }
 private async save(approved: boolean): Promise<void> {
  this.saveScene();await this.store!.save(this.folder,clone(this.doc),approved);this.status=approved?'已确认训练样本：原图与成片、参数配对保存':'草稿成片已保存';
 }
 private refresh(): void {
  this.samples=this.store!.all().filter((folder: string)=>{const m=this.store!.meta(folder);return (this.filterStatus==='all'||m.status===this.filterStatus) && JSON.stringify(m.scene).toLowerCase().includes(this.filter.toLowerCase()) && m.capture.device_model.toLowerCase().includes(this.filterDevice.toLowerCase()) && m.capture.captured_at.startsWith(this.filterDate);});this.selected=[];this.samplePage=0;
  this.presets=fs.listFileSync(this.context().filesDir+'/presets').filter((f: string)=>f.endsWith('.json'));
 }
 private visibleControls(): Control[] {
  return controls().filter((c: Control)=>this.category==='基础'?!c.path.includes('.'):this.category==='HSL'?c.path.startsWith('hsl.'):this.category==='分级'?c.path.startsWith('color_grading.'):this.category==='质感'?c.path.startsWith('texture.'):false);
 }
 private curvePoints(): number[][] {
  const curves=this.doc.parameters.tone_curves;
  if(this.curveChannel==='red')return curves.red;if(this.curveChannel==='green')return curves.green;if(this.curveChannel==='blue')return curves.blue;return curves.rgb;
 }
 private setCurve(points: number[][]): void {
  const c=this.doc.parameters.tone_curves;
  if(this.curveChannel==='red')c.red=points;else if(this.curveChannel==='green')c.green=points;else if(this.curveChannel==='blue')c.blue=points;else c.rgb=points;
 }
 private async importPreset(): Promise<void> {
  const chosen=await new picker.DocumentViewPicker(this.context()).select({maxSelectNumber:1,fileSuffixFilters:['JSON|.json']});if(!chosen.length)return;
  const f=fs.openSync(chosen[0],fs.OpenMode.READ_ONLY);
  try{if(fs.statSync(f.fd).size>65536)throw new Error('预设文件过大');const content=fs.readTextSync(chosen[0]);const d=JSON.parse(content) as Document;Imaging.validate(d);this.doc=d;this.changed();}
  finally{fs.closeSync(f);}
 }
 private async exportPreset(): Promise<void> {
  const paths=await new picker.DocumentViewPicker(this.context()).save({newFileNames:['G23_preset.json']});if(!paths.length)return;
  const f=fs.openSync(paths[0],fs.OpenMode.READ_WRITE);try{fs.writeSync(f.fd,JSON.stringify(this.doc));}finally{fs.closeSync(f);}
 }
 @Builder
 cameraUI() {
  Stack() {
   XComponent({id:'camera',type:XComponentType.SURFACE,controller:this.surface})
    .onLoad(()=>{this.surfaceId=this.surface.getXComponentSurfaceId();this.openCamera();})
    .onAreaChange((_old: Area,area: Area)=>{this.previewWidth=Number(area.width);this.previewHeight=Number(area.height);})
    .onTouch((e: TouchEvent)=>{if(e.type===TouchType.Up && e.changedTouches.length>0){try{this.camera.focus(e.changedTouches[0].x/this.previewWidth,e.changedTouches[0].y/this.previewHeight);}catch(error){this.status=(error as Error).message;}}})
   if(this.grid){Column(){Row(){Blank().width('33.3%');Divider().vertical(true);Blank().width('33.3%');Divider().vertical(true);Blank()}.height('100%')}.hitTestBehavior(HitTestMode.None)
    Column(){Blank().height('33.3%');Divider();Blank().height('33.3%');Divider();Blank()}.width('100%').height('100%').hitTestBehavior(HitTestMode.None)}
  }.layoutWeight(1).width('100%')
  Row({space:8}){
   Button('切换').onClick(()=>this.run(async()=>{this.front=!this.front;await this.openCamera();}))
   Button('闪光').enabled(this.camera.flashAvailable).onClick(()=>{try{this.camera.setFlash(!this.flash);this.flash=!this.flash;}catch(e){this.status=(e as Error).message;}})
   Button('网格').onClick(()=>this.grid=!this.grid)
   Select([{value:'4:3'},{value:'3:2'},{value:'1:1'}]).value(this.ratio).onSelect((_i:number,v:string)=>this.ratio=v)
  }
  Text('比例为后期居中裁切；原始 JPEG 保留').fontSize(11).fontColor('#adbb9d')
  if(this.camera.exposureRange.length>0){Slider({value:this.ev,min:this.camera.exposureRange[0],max:this.camera.exposureRange[this.camera.exposureRange.length-1],step:0.1}).onChange((v:number)=>{this.ev=v;this.camera.exposure(v).catch((e:Error)=>this.status=e.message);})}
  Button('● 拍摄高质量 JPEG').enabled(this.ready && !this.busy).onClick(()=>this.run(async()=>this.shoot()))
 }
 @Builder
 editorUI() {
  Row({space:4}){
   Button('对比').onClick(()=>this.compare=!this.compare)
   Button('撤销').enabled(this.cursor>0).onClick(()=>{this.doc=JSON.parse(this.history[--this.cursor]) as Document;this.changed(false);})
   Button('重做').enabled(this.cursor<this.history.length-1).onClick(()=>{this.doc=JSON.parse(this.history[++this.cursor]) as Document;this.changed(false);})
   Button('重置').onClick(()=>{this.doc=new Document();this.changed();})
  }
  Stack({alignContent:Alignment.Start}) {
   if(this.after || this.before){Image(this.showOriginal?this.before:(this.after||this.before)).objectFit(ImageFit.Contain).width('100%').height('100%').scale({x:this.zoom,y:this.zoom}).translate({x:this.panX,y:this.panY})}
   if(this.compare && this.before){Image(this.before).objectFit(ImageFit.Contain).width('100%').height('100%').scale({x:this.zoom,y:this.zoom}).translate({x:this.panX,y:this.panY}).clipShape(new Rect({width:`${this.split}%`,height:'100%'}))}
  }.width('100%').layoutWeight(1)
   .gesture(GestureGroup(GestureMode.Parallel,
    LongPressGesture().onAction(()=>this.showOriginal=true).onActionEnd(()=>this.showOriginal=false).onActionCancel(()=>this.showOriginal=false),
    PinchGesture().onActionStart(()=>this.zoomBase=this.zoom).onActionUpdate((e:GestureEvent)=>this.zoom=Math.max(1,Math.min(8,this.zoomBase*e.scale))),
    PanGesture().onActionStart(()=>{this.baseX=this.panX;this.baseY=this.panY;}).onActionUpdate((e:GestureEvent)=>{if(this.zoom>1){this.panX=this.baseX+e.offsetX;this.panY=this.baseY+e.offsetY;}})))
  if(this.compare){Slider({value:this.split,min:0,max:100}).onChange((v:number)=>this.split=v)}
  Text(this.folder.split('/').pop()||'').fontSize(10)
  Select([{value:'基础'},{value:'HSL'},{value:'曲线'},{value:'分级'},{value:'质感'},{value:'裁切'}]).value(this.category).onSelect((_i:number,v:string)=>this.category=v)
  Scroll(){Column(){
   ForEach(this.visibleControls(),(c:Control)=>{Text(c.path+' '+c.get(this.doc).toFixed(2)).fontSize(12)
    Slider({value:c.get(this.doc),min:c.min,max:c.max,step:c.step}).onChange((v:number,mode:SliderChangeMode)=>{c.set(this.doc,v);this.doc=clone(this.doc);if(mode===SliderChangeMode.End || mode===SliderChangeMode.Click)this.changed();else this.preview();})
    Button('默认值').onClick(()=>{c.set(this.doc,c.get(new Document()));this.changed();})
   },(c:Control)=>c.path)
   if(this.category==='曲线'){
    Select([{value:'rgb'},{value:'red'},{value:'green'},{value:'blue'}]).value(this.curveChannel).onSelect((_i:number,v:string)=>{this.curveChannel=v;this.curveText=JSON.stringify(this.curvePoints());})
    Text('点击添加 / 拖动移动 / 长按删除内部点；下方可精确输入。').fontSize(12)
    CurveEditor({points:this.curvePoints(),onEdit:(points:number[][]):void=>{this.setCurve(points);this.curveText=JSON.stringify(points);this.changed();}})
    TextArea({text:this.curveText}).onChange((s:string)=>this.curveText=s)
    Button('应用曲线').onClick(()=>this.run(async()=>{const previous=clone(this.doc);this.setCurve(JSON.parse(this.curveText) as number[][]);try{Imaging.validate(this.doc);this.changed();}catch(e){this.doc=previous;throw e;}}))
    Button('重置曲线').onClick(()=>{this.setCurve([[0,0],[1,1]]);this.curveText='[[0,0],[1,1]]';this.changed();})
   }
   if(this.category==='裁切'){
    Text('归一化 [left,top,right,bottom]，先裁切后旋转').fontSize(12)
    TextInput({text:this.cropText}).onChange((s:string)=>this.cropText=s)
    Button('应用裁切').onClick(()=>this.run(async()=>{const previous=clone(this.doc);this.doc.parameters.geometry.crop=JSON.parse(this.cropText) as number[];try{Imaging.validate(this.doc);this.changed();}catch(e){this.doc=previous;throw e;}}))
    Button('旋转 90°').onClick(()=>{this.doc.parameters.geometry.rotation_degrees=(this.doc.parameters.geometry.rotation_degrees+90)%360;this.changed();})
   }
  }.width('100%')}.height(190)
  Button(this.panel?'收起样本记录':'展开样本记录').onClick(()=>this.panel=!this.panel)
  if(this.panel){Scroll(){Column({space:4}){
   Text(JSON.stringify(this.doc)).fontSize(10)
   Text(this.store?JSON.stringify(this.store.meta(this.folder)):'').fontSize(10)
   Button('复制 JSON').onClick(()=>pasteboard.getSystemPasteboard().setData(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN,JSON.stringify(this.doc))))
   Text('光照：晴天/阴天/逆光/室内自然光/室内暖光/夜景/混合光/其他；英文逗号多选').fontSize(10)
   TextInput({text:this.sceneLighting,placeholder:'光照标签'}).onChange((s:string)=>this.sceneLighting=s)
   Text('内容：人像/建筑/街道/植物/天空/食物/夜景灯光/其他').fontSize(10)
   TextInput({text:this.sceneSubjects,placeholder:'内容标签，英文逗号多选'}).onChange((s:string)=>this.sceneSubjects=s)
   TextInput({text:this.notes,placeholder:'调色备注'}).onChange((s:string)=>this.notes=s)
   TextInput({text:this.styleFeatures,placeholder:'风格特征'}).onChange((s:string)=>this.styleFeatures=s)
   Rating({rating:this.rating}).stars(5).stepSize(1).onChange((n:number)=>this.rating=n)
   Toggle({type:ToggleType.Checkbox,isOn:this.satisfied}).onChange((v:boolean)=>this.satisfied=v);Text('满意')
   TextInput({text:this.presetName,placeholder:'预设名称（同名覆盖）'}).onChange((s:string)=>this.presetName=s)
   Button('保存 / 编辑命名预设').onClick(()=>this.run(async()=>{await this.store!.preset(this.presetName,this.doc);this.refresh();}))
   Button('导入预设 JSON').onClick(()=>this.run(async()=>this.importPreset()))
   Button('导出预设 JSON').onClick(()=>this.run(async()=>this.exportPreset()))
   ForEach(this.presets,(name:string)=>{Row(){Button(decodeURIComponent(name.slice(0,-5))).onClick(()=>this.run(async()=>{const d=JSON.parse(Store.read(this.context().filesDir+'/presets/'+name)) as Document;Imaging.validate(d);this.doc=d;this.changed();}));Button('删除').onClick(()=>{fs.unlinkSync(this.context().filesDir+'/presets/'+name);this.refresh();})}},(name:string)=>name)
  }}.height(190)}
  Row({space:6}){Button('保存草稿').onClick(()=>this.run(async()=>this.save(false)));Button('保存为 G23 训练样本').onClick(()=>this.run(async()=>this.save(true)))}
 }
 @Builder
 datasetUI() {
  Row(){Select([{value:'all'},{value:'approved'},{value:'draft'}]).value(this.filterStatus).onSelect((_i:number,v:string)=>{this.filterStatus=v;this.refresh();});Button('全选').onClick(()=>this.selected=this.samples.slice())}
  TextInput({text:this.filter,placeholder:'场景 / 内容标签'}).onChange((s:string)=>{this.filter=s;this.refresh();})
  TextInput({text:this.filterDevice,placeholder:'设备型号'}).onChange((s:string)=>{this.filterDevice=s;this.refresh();})
  TextInput({text:this.filterDate,placeholder:'日期 YYYY-MM-DD / 年月前缀'}).onChange((s:string)=>{this.filterDate=s;this.refresh();})
  Grid(){ForEach(this.samples.slice(this.samplePage*36,(this.samplePage+1)*36),(folder:string)=>{GridItem(){Column(){
   Image('file://'+folder+'/thumbnail_after.jpg').height(110).objectFit(ImageFit.Cover)
   Text(folder.split('/').pop()||'').fontSize(10).maxLines(1)
   Text(this.store!.meta(folder).status).fontSize(11)
   Toggle({type:ToggleType.Checkbox,isOn:this.selected.includes(folder)}).onChange((v:boolean)=>{this.selected=v?[...this.selected,folder]:this.selected.filter((s:string)=>s!==folder);})
   Button('重新编辑 / 对照').onClick(()=>this.run(async()=>this.edit(folder)))
  }}} ,(folder:string)=>folder)}.columnsTemplate('1fr 1fr').columnsGap(8).rowsGap(8).layoutWeight(1)
  Row(){Button('上一页').enabled(this.samplePage>0).onClick(()=>this.samplePage--);Text(`${this.samplePage+1} / ${Math.max(1,Math.ceil(this.samples.length/36))}`);Button('下一页').enabled((this.samplePage+1)*36<this.samples.length).onClick(()=>this.samplePage++)}
  Row(){Toggle({type:ToggleType.Checkbox,isOn:this.approvedOnly}).onChange((v:boolean)=>this.approvedOnly=v);Text('仅导出 approved')}
  Button('导出 ZIP 并分享 / 保存').onClick(()=>{
   AlertDialog.show({message:'原始 JPEG 可能含 GPS。本鸿蒙版本尚未实现无损移除 GPS；确认后保留原文件导出。',primaryButton:{value:'取消',action:():void=>{}},secondaryButton:{value:'确认保留 EXIF 导出',action:():void=>{this.run(async()=>{const path=await Exporter.export(this.store!,this.selected,this.approvedOnly,(n:number,t:number)=>this.status=`导出 ${n}/${t}`);await Exporter.share(this.context(),path);});}}});
  })
  Button('删除所选').onClick(()=>AlertDialog.show({message:'永久删除所选样本和原图？',primaryButton:{value:'取消',action:():void=>{}},secondaryButton:{value:'删除',action:():void=>{this.selected.forEach((folder:string)=>fs.rmdirSync(folder));this.refresh();}}}))
 }
 build() {
  Column({space:8}){
   Text('G23 Lab / '+this.page).fontSize(22).fontWeight(FontWeight.Medium).width('100%')
   Row({space:12}){Button('Camera').onClick(()=>this.run(async()=>this.navigate('Camera')));Button('Dataset').onClick(()=>this.run(async()=>this.navigate('Dataset')))}
   if(this.page==='Camera'){this.cameraUI()}else if(this.page==='Editor'){this.editorUI()}else{this.datasetUI()}
   Text(this.busy?'处理中… '+this.status:this.status).fontSize(10).fontColor('#adbb9d').maxLines(4).width('100%')
  }.width('100%').height('100%').padding({left:12,right:12,top:32,bottom:24}).backgroundColor('#101110').enabled(!this.busy)
 }
}
