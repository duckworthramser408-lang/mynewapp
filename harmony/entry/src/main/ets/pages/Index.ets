import { CurveEditor } from './CurveEditor';
import { LutWheel } from './LutWheel';
import { FilmLut, CubeTable } from '../model/LutLibrary';
import { parameterPairs, parameterName, ParameterPair, DisplayPair, capturePairs, statisticsPairs, Comparison } from '../model/Analysis';
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
 @State films: FilmLut[] = [];
 @State lutName: string = '';
 @State chosenIndex: number = 0;
 @State browsing?: image.PixelMap = undefined;
 @State photoIndex: number = 0;
 private previewLut?: CubeTable;
 @State status: string = '连接相机后显示设备能力';
 @State ready: boolean = false;
 @State settings: boolean = false;
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
 aboutToAppear(): void {this.context().eventHub.on('g23.background',this.backgroundCallback);this.context().eventHub.on('g23.foreground',this.foregroundCallback);this.store=new Store(this.context());this.films=this.store.looks.items;this.lutName=this.store.looks.selected().name;this.refresh();}
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
  const look=this.store!.looks.selected();const ratio=this.ratio;
  const bytes=await this.camera.capture();this.folder=await this.store!.capture(bytes,this.camera.cameraId);
  await this.store!.looks.attach(this.folder,look);const d=this.store!.params(this.folder);const source=await Imaging.decode(this.folder+'/original.jpg',640);
  try{const info=await source.getImageInfo();const landscape=ratio==='3:2'?1.5:ratio==='1:1'?1:4/3;const desired=info.size.width<info.size.height?1/landscape:landscape;const actual=info.size.width/info.size.height;
   d.parameters.geometry.crop=actual>desired?[(1-desired/actual)/2,0,(1+desired/actual)/2,1]:[0,(1-actual/desired)/2,1,(1+actual/desired)/2];
  }finally{await source.release();}
  Store.text(this.folder+'/parameters.json',JSON.stringify(d));await this.store!.save(this.folder,d,false);this.status='已保存 · '+look.name;this.refresh();
 }
 private async edit(folder: string): Promise<void> {
  this.renderToken++;this.pending=false;
  const previousAfter=this.after;this.after=undefined;await previousAfter?.release();
  await this.camera.close();this.ready=false;this.folder=folder;this.doc=this.store!.params(folder);
  this.history=[JSON.stringify(this.doc)];this.cursor=0;
  if(fs.accessSync(folder+'/history.json')){try{const saved=JSON.parse(Store.read(folder+'/history.json')) as HistoryState;if(saved.cursor>=0&&saved.cursor<saved.states.length&&saved.states[saved.cursor]===JSON.stringify(this.doc)){this.history=saved.states;this.cursor=saved.cursor;}}catch(e){this.status='历史文件不可读；已恢复最终参数快照';}}
  this.previewLut=await this.store!.looks.forPhoto(folder);this.page='Editor';
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
   try{const output=await Imaging.render(this.folder+'/original.jpg',snapshot,640,this.previewLut);
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
  this.samples=this.store!.all().filter((folder:string)=>fs.accessSync(folder+'/edited.png')||(!fs.accessSync(folder+'/look.json')&&fs.accessSync(folder+'/original.jpg')));
  this.presets=fs.listFileSync(this.context().filesDir+'/presets').filter((f:string)=>f.endsWith('.json'));
 }
 private async openLuts(): Promise<void> {this.chosenIndex=this.films.findIndex((v:FilmLut)=>v.id===this.store!.looks.selected().id);await this.navigate('LUT');}
 private async finishLut(): Promise<void> {await this.store!.looks.select(this.films[this.chosenIndex]);this.lutName=this.films[this.chosenIndex].name;await this.navigate('Camera');}
 private async openPhoto(index:number): Promise<void> {
  if(index<0||index>=this.samples.length)return;await this.navigate('Photo');this.photoIndex=index;
  const token=this.renderToken;const folder=this.samples[index];const file=folder+(fs.accessSync(folder+'/edited.png')?'/edited.png':'/original.jpg');
  const pixel=await Imaging.decode(file,2048);if(this.page==='Photo'&&token===this.renderToken){const old=this.browsing;this.browsing=pixel;this.zoom=1;this.panX=0;this.panY=0;await old?.release();}else await pixel.release();
 }
 onBackPress(): boolean {
  if(this.busy)return true;
  if(this.page==='LUT'){this.run(async()=>this.finishLut());return true;}
  if(this.page==='Photo'){this.run(async()=>this.navigate('Dataset'));return true;}
  if(this.page!=='Camera'){this.run(async()=>this.navigate('Camera'));return true;}
  return false;
 }
 private visibleControls(): Control[] {
  return controls().filter((c: Control)=>this.category==='基础'?!c.path.includes('.'):this.category==='分色'?c.path.startsWith('hsl.'):this.category==='分级'?c.path.startsWith('color_grading.'):this.category==='质感'?c.path.startsWith('texture.'):false);
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
 private async exportSelected(): Promise<void> {
  const targets=this.selected.length?this.selected:this.samples;
  for(const target of targets)await this.store!.save(target,this.store!.params(target),this.store!.meta(target).status==='approved');
  const path=await Exporter.export(this.store!,targets,false,(n:number,t:number)=>this.status=`导出 ${n}/${t}`);
  await Exporter.save(this.context(),path);this.status='导出流程完成';
 }
 private async exportCurrent(jsonOnly: boolean=false): Promise<void> {
  this.saveScene();const approved=this.store!.meta(this.folder).status==='approved';
  await this.store!.save(this.folder,clone(this.doc),approved);
  if(jsonOnly){await Exporter.save(this.context(),this.folder+'/comparison.json');return;}
  const path=await Exporter.export(this.store!,[this.folder],false,(_n:number,_t:number)=>{});await Exporter.save(this.context(),path);
 }
 private dataRows(): ParameterPair[] {return this.store && this.folder.length?parameterPairs(this.store.originalParams(this.folder),this.doc):[];}
 private captureRows(): DisplayPair[] {return this.store && this.folder.length?capturePairs(this.store.meta(this.folder).capture):[];}
 private statisticsRows(): DisplayPair[] {
  const path=this.folder+'/comparison.json';if(!fs.accessSync(path))return [];
  const data=JSON.parse(Store.read(path)) as Comparison;if(JSON.stringify(data.after.parameters)!==JSON.stringify(this.doc))return [];
  return statisticsPairs(data);
 }
 @Builder
 cameraUI() {
  Row(){Button('闪光').fontSize(13).backgroundColor('#222627').onClick(()=>{try{this.camera.setFlash(!this.flash);this.flash=!this.flash;}catch(e){this.status=(e as Error).message;}})
   Button(this.lutName+' ⌄').fontSize(16).fontColor('#B8C7A8').backgroundColor('#0F1112').layoutWeight(1).accessibilityText('选择 LUT，当前：'+this.lutName).onClick(()=>this.run(async()=>this.openLuts()))
   Button('设置').fontSize(13).backgroundColor('#222627').onClick(()=>this.settings=!this.settings)
  }.height(58).width('100%')
  if(this.settings){Column({space:10}){
   Row(){Text('构图网格').layoutWeight(1);Toggle({type:ToggleType.Switch,isOn:this.grid}).onChange((v:boolean)=>this.grid=v)}
   Text(this.status).fontSize(12).fontColor('#949C9B')
   Text('原图始终保留；导出可另存到系统文件。').fontSize(12)
   Button('最近照片的调色与数据').onClick(()=>this.run(async()=>{const recent=this.store!.all()[0];if(recent)await this.edit(recent);}));Button('导出全部照片与分析').onClick(()=>this.run(async()=>{this.selected=this.store!.all();await this.exportSelected();}))
  }.padding(16).backgroundColor('#191C1D').borderRadius(16)}
  Stack() {
   XComponent({id:'camera',type:XComponentType.SURFACE,controller:this.surface})
    .onLoad(()=>{this.surfaceId=this.surface.getXComponentSurfaceId();this.openCamera();})
    .onAreaChange((_old: Area,area: Area)=>{this.previewWidth=Number(area.width);this.previewHeight=Number(area.height);})
    .onTouch((e: TouchEvent)=>{if(e.type===TouchType.Up && e.changedTouches.length>0){try{this.camera.focus(e.changedTouches[0].x/this.previewWidth,e.changedTouches[0].y/this.previewHeight);}catch(error){this.status=(error as Error).message;}}})
   if(this.grid){Column(){Row(){Blank().width('33.3%');Divider().vertical(true);Blank().width('33.3%');Divider().vertical(true);Blank()}.height('100%')}.hitTestBehavior(HitTestMode.None)
    Column(){Blank().height('33.3%');Divider();Blank().height('33.3%');Divider();Blank()}.width('100%').height('100%').hitTestBehavior(HitTestMode.None)}
   if(!this.ready){Text('正在连接相机').fontSize(13).fontColor('#949C9B')}
  }.layoutWeight(1).width('100%').borderRadius(12).clip(true)
  Row({space:20}){ForEach(['4:3','3:2','1:1'],(value:string)=>{Text(value).fontSize(13).fontColor(this.ratio===value?'#B8C7A8':'#949C9B').height(44).onClick(()=>this.ratio=value)})}.justifyContent(FlexAlign.Center).width('100%')
  if(this.settings && this.camera.exposureRange.length>0){Text('曝光补偿').fontSize(12);Slider({value:this.ev,min:this.camera.exposureRange[0],max:this.camera.exposureRange[this.camera.exposureRange.length-1],step:0.1}).onChange((v:number)=>{this.ev=v;this.camera.exposure(v).catch((e:Error)=>this.status=e.message);})}
  Stack({alignContent:Alignment.Center}){
   Row(){Button('相册').width(68).height(64).fontSize(13).backgroundColor('#191C1D').onClick(()=>this.run(async()=>this.navigate('Dataset')));Blank();Button('翻转').width(68).height(64).fontSize(13).backgroundColor('#191C1D').onClick(()=>this.run(async()=>{this.front=!this.front;await this.openCamera();}))}.width('100%')
   Button('',{type:ButtonType.Circle}).width(80).height(80).backgroundColor('#EBEBE2').border({width:5,color:'#313636'}).accessibilityText('拍照').enabled(this.ready && !this.busy).onClick(()=>this.run(async()=>this.shoot()))
  }.width('100%').height(100)
  Text('高画质拍摄 · 原图始终保留').fontSize(11).fontColor('#949C9B')
 }
 @Builder
 editorUI() {
  Row({space:4}){
   Button('并列数据').onClick(()=>this.panel=!this.panel)
   Button('撤销').enabled(this.cursor>0).onClick(()=>{this.doc=JSON.parse(this.history[--this.cursor]) as Document;this.changed(false);})
   Button('重做').enabled(this.cursor<this.history.length-1).onClick(()=>{this.doc=JSON.parse(this.history[++this.cursor]) as Document;this.changed(false);})
   Button('重置').onClick(()=>{this.doc=new Document();this.changed();})
  }
  Row({space:8}){
   Column({space:6}){Text('原图').fontSize(11).fontColor('#949C9B');if(this.before){Image(this.before).objectFit(ImageFit.Contain).width('100%').layoutWeight(1).scale({x:this.zoom,y:this.zoom}).translate({x:this.panX,y:this.panY})}}.layoutWeight(1).height('100%').clip(true)
   Column({space:6}){Text('调色后').fontSize(11).fontColor('#949C9B');if(this.after||this.before){Image(this.showOriginal?this.before:(this.after||this.before)).objectFit(ImageFit.Contain).width('100%').layoutWeight(1).scale({x:this.zoom,y:this.zoom}).translate({x:this.panX,y:this.panY})}}.layoutWeight(1).height('100%').clip(true)
  }.width('100%').layoutWeight(1)
   .gesture(GestureGroup(GestureMode.Parallel,
    LongPressGesture().onAction(()=>this.showOriginal=true).onActionEnd(()=>this.showOriginal=false).onActionCancel(()=>this.showOriginal=false),
    PinchGesture().onActionStart(()=>this.zoomBase=this.zoom).onActionUpdate((e:GestureEvent)=>this.zoom=Math.max(1,Math.min(8,this.zoomBase*e.scale))),
    PanGesture().onActionStart(()=>{this.baseX=this.panX;this.baseY=this.panY;}).onActionUpdate((e:GestureEvent)=>{if(this.zoom>1){this.panX=this.baseX+e.offsetX;this.panY=this.baseY+e.offsetY;}})))
  Text(this.folder.split('/').pop()||'').fontSize(10)
  Select([{value:'基础'},{value:'分色'},{value:'曲线'},{value:'分级'},{value:'质感'},{value:'裁切'}]).value(this.category).onSelect((_i:number,v:string)=>this.category=v)
  Scroll(){Column(){
   ForEach(this.visibleControls(),(c:Control)=>{Text(parameterName(c.path)+' '+c.get(this.doc).toFixed(2)).fontSize(12)
    Slider({value:c.get(this.doc),min:c.min,max:c.max,step:c.step}).onChange((v:number,mode:SliderChangeMode)=>{c.set(this.doc,v);this.doc=clone(this.doc);if(mode===SliderChangeMode.End || mode===SliderChangeMode.Click)this.changed();else this.preview();})
    Button('默认值').onClick(()=>{c.set(this.doc,c.get(new Document()));this.changed();})
   },(c:Control)=>c.path)
   if(this.category==='曲线'){
    Select([{value:'总曲线'},{value:'红色'},{value:'绿色'},{value:'蓝色'}]).value(['总曲线','红色','绿色','蓝色'][['rgb','red','green','blue'].indexOf(this.curveChannel)]).onSelect((i:number,_v:string)=>{this.curveChannel=['rgb','red','green','blue'][i];this.curveText=JSON.stringify(this.curvePoints());})
    Text('点击添加 / 拖动移动 / 长按删除内部点；下方可精确输入。').fontSize(12)
    CurveEditor({points:this.curvePoints(),onEdit:(points:number[][]):void=>{this.setCurve(points);this.curveText=JSON.stringify(points);this.changed();}})
    TextArea({text:this.curveText}).onChange((s:string)=>this.curveText=s)
    Button('应用曲线').onClick(()=>this.run(async()=>{const previous=clone(this.doc);this.setCurve(JSON.parse(this.curveText) as number[][]);try{Imaging.validate(this.doc);this.changed();}catch(e){this.doc=previous;throw e;}}))
    Button('重置曲线').onClick(()=>{this.setCurve([[0,0],[1,1]]);this.curveText='[[0,0],[1,1]]';this.changed();})
   }
   if(this.category==='裁切'){
    Text('归一化坐标：左、上、右、下；先裁切后旋转').fontSize(12)
    TextInput({text:this.cropText}).onChange((s:string)=>this.cropText=s)
    Button('应用裁切').onClick(()=>this.run(async()=>{const previous=clone(this.doc);this.doc.parameters.geometry.crop=JSON.parse(this.cropText) as number[];try{Imaging.validate(this.doc);this.changed();}catch(e){this.doc=previous;throw e;}}))
    Button('旋转 90°').onClick(()=>{this.doc.parameters.geometry.rotation_degrees=(this.doc.parameters.geometry.rotation_degrees+90)%360;this.changed();})
   }
  }.width('100%')}.height(190)
  Button(this.panel?'收起样本记录':'展开样本记录').onClick(()=>this.panel=!this.panel)
  if(this.panel){Scroll(){Column({space:4}){
   Row(){Text('参数').fontSize(12).fontColor('#949C9B').layoutWeight(1);Text('原始').fontSize(12).fontColor('#949C9B').layoutWeight(1);Text('调色后').fontSize(12).fontColor('#949C9B').layoutWeight(1)}
   Text('拍摄记录 · 两侧共用同一次拍摄').fontSize(12).fontColor('#B8C7A8')
   ForEach(this.captureRows(),(p:DisplayPair)=>{Row({space:6}){Text(p.label).fontSize(11).layoutWeight(1);Text(p.before).fontSize(11).layoutWeight(1);Text(p.after).fontSize(11).layoutWeight(1)}.padding({top:8,bottom:8})},(p:DisplayPair)=>p.label)
   Text('胶卷色调：'+(this.store!.looks.look(this.folder)?.name||'未应用')).fontSize(12).fontColor('#B8C7A8')
   Text('色调参数 · 原始基线与当前快照').fontSize(12).fontColor('#B8C7A8')
   ForEach(this.dataRows(),(p:ParameterPair)=>{Row({space:6}){Text(p.label).fontSize(11).layoutWeight(1);Text(JSON.stringify(p.before)).fontSize(11).layoutWeight(1);Text(JSON.stringify(p.after)).fontSize(11).layoutWeight(1)}.padding({top:8,bottom:8})},(p:ParameterPair)=>p.parameter)
   Text('图像统计 · 保存后生成，固定网格最多 256×256 点').fontSize(12).fontColor('#B8C7A8')
   ForEach(this.statisticsRows(),(p:DisplayPair)=>{Row({space:6}){Text(p.label).fontSize(11).layoutWeight(1);Text(p.before).fontSize(11).layoutWeight(1);Text(p.after).fontSize(11).layoutWeight(1)}.padding({top:8,bottom:8})},(p:DisplayPair)=>p.label)
   Button('保存分析文件').onClick(()=>this.run(async()=>this.exportCurrent(true)))
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
  Button('一键导出 · 保存到文件').backgroundColor('#B8C7A8').fontColor('#0F1112').onClick(()=>this.run(async()=>this.exportCurrent()))
  Row({space:6}){Button('保存成片').onClick(()=>this.run(async()=>this.save(false)));Button('确认训练样本').onClick(()=>this.run(async()=>this.save(true)))}
 }
 @Builder
 datasetUI() {
  Grid(){ForEach(this.samples,(folder:string)=>{GridItem(){Image('file://'+folder+(fs.accessSync(folder+'/thumbnail_after.jpg')?'/thumbnail_after.jpg':'/original.jpg')).width('100%').aspectRatio(1).objectFit(ImageFit.Cover).onClick(()=>this.run(async()=>this.openPhoto(this.samples.indexOf(folder))))}},(folder:string)=>folder)}.columnsTemplate('1fr 1fr 1fr').columnsGap(4).rowsGap(4).layoutWeight(1)
 }
 @Builder
 photoUI() {
  if(this.browsing){Image(this.browsing).objectFit(ImageFit.Contain).width('100%').layoutWeight(1).scale({x:this.zoom,y:this.zoom}).translate({x:this.panX,y:this.panY}).clip(true)
   .gesture(GestureGroup(GestureMode.Parallel,PinchGesture().onActionStart(()=>this.zoomBase=this.zoom).onActionUpdate((e:GestureEvent)=>this.zoom=Math.max(1,Math.min(8,this.zoomBase*e.scale))),PanGesture().onActionStart(()=>{this.baseX=this.panX;this.baseY=this.panY;}).onActionUpdate((e:GestureEvent)=>{if(this.zoom>1){this.panX=this.baseX+e.offsetX;this.panY=this.baseY+e.offsetY;}}).onActionEnd((e:GestureEvent)=>{if(this.zoom<=1&&Math.abs(e.offsetX)>64)this.run(async()=>this.openPhoto(this.photoIndex+(e.offsetX<0?1:-1)));})))}else{Blank().layoutWeight(1)}
 }
 @Builder
 lutUI() {
  LutWheel({films:this.films,initialIndex:this.chosenIndex,onSelect:(index:number):void=>{this.chosenIndex=index;}}).layoutWeight(1).width('100%')
  Text('上下拨动 · 自动吸附 · 箭头指向当前选择').fontSize(11).fontColor('#949C9B').height(40)
  Button('使用并返回拍摄').width('100%').height(48).fontColor('#0F1112').backgroundColor('#B8C7A8').onClick(()=>this.run(async()=>this.finishLut()))
 }
 build() {
  Column({space:8}){
   if(this.page!=='Camera'){Row(){Button('返回').fontSize(13).backgroundColor('#222627').onClick(()=>this.run(async()=>{if(this.page==='LUT')await this.finishLut();else await this.navigate(this.page==='Photo'?'Dataset':'Camera');}));Text(this.page==='LUT'?'胶卷色调':this.page==='Photo'?'照片':this.page==='Editor'?'影像调色':'相册').fontSize(20).fontColor('#EBEBE2').layoutWeight(1)}}
   if(this.page==='Camera'){this.cameraUI()}else if(this.page==='LUT'){this.lutUI()}else if(this.page==='Photo'){this.photoUI()}else if(this.page==='Editor'){this.editorUI()}else{this.datasetUI()}
   if(this.busy || this.page==='Editor'){Text(this.busy?'处理中…':this.status).fontSize(11).fontColor('#949C9B').maxLines(2).width('100%')}
  }.width('100%').height('100%').padding({left:12,right:12,top:32,bottom:24}).backgroundColor('#0F1112').enabled(!this.busy)
 }
}
