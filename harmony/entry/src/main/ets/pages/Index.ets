import { LutWheel } from './LutWheel';
import { FilmLut } from '../model/LutLibrary';
import { PhotoStore, CameraPhoto } from '../model/PhotoStore';
import { CameraService } from '../model/CameraService';
import { Imaging } from '../model/Imaging';
import { Document } from '../model/Model';
import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';

@Entry
@Component
struct Index {
 @State page: string = 'Camera';
 @State films: FilmLut[] = [];
 @State lutName: string = '';
 @State chosenIndex: number = 0;
 @State ready: boolean = false;
 @State capturing: boolean = false;
 @State settings: boolean = false;
 @State status: string = '正在连接相机';
 @State front: boolean = false;
 @State flash: boolean = false;
 @State grid: boolean = true;
 @State ratio: string = '4:3';
 @State ev: number = 0;
 @State photos: CameraPhoto[] = [];
 @State current?: CameraPhoto = undefined;
 @State before?: image.PixelMap = undefined;
 @State after?: image.PixelMap = undefined;
 @State split: number = 50;
 @State zoom: number = 1;
 @State saveSheet: boolean = false;
 @State saveKind: string = '胶片';
 @State previewWidth: number = 1;
 @State previewHeight: number = 1;
 private camera: CameraService = new CameraService();
 private surface: XComponentController = new XComponentController();
 private surfaceId: string = '';
 private store?: PhotoStore;
 private token: number = 0;
 private photoToken: number = 0;
 private baseSplit: number = 50;
 private zoomBase: number = 1;
 private photoKey: string = '';
 private context(): common.UIAbilityContext {return getContext(this) as common.UIAbilityContext;}
 private backgroundCallback: () => void = (): void => {this.ready=false;this.camera.close().catch((e:Error)=>console.error(e.message));};
 private foregroundCallback: () => void = (): void => {if(this.page==='Camera'&&this.surfaceId.length)this.openCamera();this.store?.recover();};
 private photosCallback: () => void = (): void => {this.refresh();if(this.page==='Photo')this.loadPhoto().catch((e:Error)=>this.error(e));};
 aboutToAppear(): void {
  this.store=PhotoStore.get(this.context());this.films=this.store.looks.items;this.lutName=this.store.looks.selected().name;
  this.context().eventHub.on('g23.background',this.backgroundCallback);this.context().eventHub.on('g23.foreground',this.foregroundCallback);this.context().eventHub.on('g23.photos',this.photosCallback);
  this.store.recover();this.refresh();
 }
 aboutToDisappear(): void {
  this.token++;this.photoToken++;this.context().eventHub.off('g23.background',this.backgroundCallback);this.context().eventHub.off('g23.foreground',this.foregroundCallback);this.context().eventHub.off('g23.photos',this.photosCallback);
  this.camera.close().catch((e:Error)=>console.error(e.message));this.releasePreview();
 }
 private error(e:Error): void {AlertDialog.show({message:e.message,confirm:{value:'确定',action:():void=>{}}});}
 private refresh(): void {this.photos=this.store!.photos();}
 private async openCamera(): Promise<void> {
  this.ready=false;
  try{await this.camera.open(this.context(),this.surfaceId,this.front);this.ready=true;this.status=this.camera.capability;}
  catch(e){this.status=(e as Error).message;}
 }
 private async navigate(page:string): Promise<void> {
  if(this.capturing)return;
  this.token++;this.photoToken++;this.photoKey='';this.saveSheet=false;
  await this.camera.close();this.ready=false;await this.releasePreview();this.page=page;this.refresh();
 }
 private async releasePreview(): Promise<void> {
  const a=this.before,b=this.after;this.before=undefined;this.after=undefined;await a?.release();if(b!==a)await b?.release();
 }
 private async shoot(): Promise<void> {
  if(this.capturing||!this.ready)return;
  const look=this.store!.looks.selected(),ratio=this.ratio;this.capturing=true;
  try {
   const bytes=await this.camera.capture();
   await this.store!.capture(bytes,look,ratio);
   // The application queue owns development; the camera session stays open.
   this.refresh();
  }catch(e){this.error(e as Error);}finally{this.capturing=false;}
 }
 private async openLuts(): Promise<void> {this.chosenIndex=this.films.findIndex((v:FilmLut)=>v.id===this.store!.looks.selected().id);await this.navigate('LUT');}
 private async finishLut(): Promise<void> {await this.store!.looks.select(this.films[this.chosenIndex]);this.lutName=this.films[this.chosenIndex].name;await this.navigate('Camera');}
 private async openPhoto(photo:CameraPhoto): Promise<void> {await this.navigate('Photo');this.current=photo;this.split=50;this.zoom=1;await this.loadPhoto();}
 private async loadPhoto(): Promise<void> {
  const folder=this.current?.folder;if(!folder)return;
  const fresh=this.store!.photos().find((v:CameraPhoto)=>v.folder===folder);if(!fresh){await this.navigate('Album');return;}
  this.current=fresh;
  const key=fresh.folder+':'+fresh.state;if(key===this.photoKey)return;
  const token=++this.photoToken;const navigation=this.token;
  let original: image.PixelMap|undefined=undefined;let film:image.PixelMap|undefined=undefined;
  try {
   original=await Imaging.decode(fresh.original(),1600);
   if(fresh.ratio==='legacy'&&fs.accessSync(folder+'/parameters.json'))await Imaging.geometry(original,JSON.parse(fs.readTextSync(folder+'/parameters.json')) as Document);
   else await Imaging.cropRatio(original,fresh.ratio);
   const path=fresh.film();if(path.length)film=await Imaging.decode(path,1600);
   if(token===this.photoToken&&navigation===this.token&&this.page==='Photo') {
    await this.releasePreview();if(token===this.photoToken&&navigation===this.token&&this.page==='Photo'){this.before=original;this.after=film;original=undefined;film=undefined;this.photoKey=key;}
   }
  }finally{await original?.release();await film?.release();}
 }
 private deletePhoto(photo:CameraPhoto): void {
  AlertDialog.show({title:'删除这张照片？',message:'应用内的原片与胶片会一起删除，系统相册已保存的副本保留。',
   primaryButton:{value:'取消',action:():void=>{}},secondaryButton:{value:'删除',action:():void=>{
    try{this.store!.delete(photo.folder);if(this.page==='Photo')this.navigate('Album').catch((e:Error)=>this.error(e));else this.refresh();}catch(e){this.error(e as Error);}
   }}});
 }
 private async saveToGallery(): Promise<void> {
  const photo=this.current;if(!photo)return;
  const path=this.saveKind==='原片'?photo.original():photo.film();if(!path.length)throw new Error('胶片尚未冲洗完成');
  // Invoke immediately inside a successful SaveButton callback, using its temporary grant.
  const helper=photoAccessHelper.getPhotoAccessHelper(this.context());
  const request=photoAccessHelper.MediaAssetChangeRequest.createImageAssetRequest(this.context(),fileUri.getUriFromPath(path));
  await helper.applyChanges(request);this.saveSheet=false;
  AlertDialog.show({message:this.saveKind+'已保存到系统相册',confirm:{value:'完成',action:():void=>{}}});
 }
 onBackPress(): boolean {
  if(this.capturing)return true;
  if(this.saveSheet){this.saveSheet=false;return true;}
  if(this.page==='LUT'){this.finishLut().catch((e:Error)=>this.error(e));return true;}
  if(this.page==='Photo'){this.navigate('Album').catch((e:Error)=>this.error(e));return true;}
  if(this.page==='Album'){this.navigate('Camera').catch((e:Error)=>this.error(e));return true;}
  return false;
 }
 @Builder
 filmPlaceholder(label:string) {
  Column({space:10}) {
   Stack() {
    Rect().width(44).height(62).radius(5).fill(Color.Transparent).linearGradient({angle:90,colors:[['#343C34',0],['#B8C7A8',0.45],['#2E372D',1]]})
    Column(){Rect().width(50).height(8).radius(3).fill('#313636');Blank();Rect().width(50).height(8).radius(3).fill('#313636')}.height(70)
    Text('35').fontSize(13).fontColor('#B8C7A8').width(30).height(26).textAlign(TextAlign.Center).backgroundColor('#0F1112').borderRadius(3)
   }.width(62).height(76)
   Text(label).fontSize(12).fontColor('#EBEBE2')
  }.width('100%').height('100%').justifyContent(FlexAlign.Center).backgroundColor('#191C1D')
 }
 @Builder
 cameraUI() {
  Row() {
   Button('闪光').fontSize(13).backgroundColor('#222627').enabled(!this.capturing).onClick(()=>{try{this.camera.setFlash(!this.flash);this.flash=!this.flash;}catch(e){this.error(e as Error);}})
   Button(this.lutName+' ⌄').fontSize(16).fontColor('#B8C7A8').backgroundColor('#0F1112').layoutWeight(1).accessibilityText('选择 LUT，当前：'+this.lutName).enabled(!this.capturing).onClick(()=>this.openLuts().catch((e:Error)=>this.error(e)))
   Button('设置').fontSize(13).backgroundColor('#222627').onClick(()=>this.settings=!this.settings)
  }.height(58).width('100%')
  if(this.settings){Column({space:10}) {
   Row(){Text('构图网格').layoutWeight(1);Toggle({type:ToggleType.Switch,isOn:this.grid}).onChange((v:boolean)=>this.grid=v)}
   Text('照片保存在应用相册，预览时可选择保存原片或胶片到系统相册。').fontSize(12).fontColor('#949C9B')
   Text(this.status).fontSize(12).fontColor('#949C9B')
  }.padding(16).backgroundColor('#191C1D').borderRadius(16)}
  Stack() {
   XComponent({id:'camera',type:XComponentType.SURFACE,controller:this.surface})
    .onLoad(()=>{this.surfaceId=this.surface.getXComponentSurfaceId();this.openCamera();})
    .onAreaChange((_old:Area,area:Area)=>{this.previewWidth=Number(area.width);this.previewHeight=Number(area.height);})
    .onTouch((e:TouchEvent)=>{if(e.type===TouchType.Up&&e.changedTouches.length){try{this.camera.focus(e.changedTouches[0].x/this.previewWidth,e.changedTouches[0].y/this.previewHeight);}catch(error){this.error(error as Error);}}})
   if(this.grid) {
    Row(){Blank().width('33.3%');Divider().vertical(true);Blank().width('33.3%');Divider().vertical(true);Blank()}.width('100%').height('100%').hitTestBehavior(HitTestMode.None)
    Column(){Blank().height('33.3%');Divider();Blank().height('33.3%');Divider();Blank()}.width('100%').height('100%').hitTestBehavior(HitTestMode.None)
   }
   if(!this.ready){Text(this.status).fontSize(13).fontColor('#949C9B').padding(16)}
  }.layoutWeight(1).width('100%').borderRadius(12).clip(true)
  Row({space:20}){ForEach(['4:3','3:2','1:1'],(value:string)=>{Text(value).fontSize(13).fontColor(this.ratio===value?'#B8C7A8':'#949C9B').height(44).onClick(()=>{if(!this.capturing)this.ratio=value;})})}.justifyContent(FlexAlign.Center).width('100%')
  if(this.settings&&this.camera.exposureRange.length>0){Text('曝光补偿').fontSize(12);Slider({value:this.ev,min:this.camera.exposureRange[0],max:this.camera.exposureRange[this.camera.exposureRange.length-1],step:0.1}).onChange((v:number)=>{this.ev=v;this.camera.exposure(v).catch((e:Error)=>this.error(e));})}
  Stack({alignContent:Alignment.Center}) {
   Row(){Button('相册').width(68).height(64).fontSize(13).backgroundColor('#191C1D').enabled(!this.capturing).onClick(()=>this.navigate('Album').catch((e:Error)=>this.error(e)));Blank();Button('翻转').width(68).height(64).fontSize(13).backgroundColor('#191C1D').enabled(!this.capturing).onClick(()=>{this.front=!this.front;this.openCamera();})}.width('100%')
   Button('',{type:ButtonType.Circle}).width(80).height(80).backgroundColor('#EBEBE2').border({width:5,color:'#313636'}).accessibilityText('拍照').enabled(this.ready&&!this.capturing).onClick(()=>this.shoot())
  }.width('100%').height(100)
  Text('留住此刻 · 原片与胶片').fontSize(11).fontColor('#949C9B')
 }
 @Builder
 albumUI() {
  Row(){Button('返回').fontSize(13).backgroundColor('#222627').onClick(()=>this.navigate('Camera'));Text('相册').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)}.height(58).width('100%')
  if(!this.photos.length){Text('还没有照片，拍下第一张胶片吧').fontColor('#949C9B').layoutWeight(1)}
  else {Grid(){ForEach(this.photos,(photo:CameraPhoto)=>{
   GridItem(){
    if(photo.developing()||photo.state==='failed') {this.filmPlaceholder(photo.state==='failed'?'冲洗未完成':'冲洗中')}
    else {Image(fileUri.getUriFromPath(photo.thumbnail())).width('100%').height('100%').objectFit(ImageFit.Cover)}
   }.aspectRatio(1).borderRadius(4).clip(true).onClick(()=>this.openPhoto(photo).catch((e:Error)=>this.error(e)))
    .gesture(LongPressGesture().onAction(()=>this.deletePhoto(photo)))
  },(photo:CameraPhoto)=>photo.folder+':'+photo.state)}.columnsTemplate('1fr 1fr 1fr').columnsGap(3).rowsGap(3).width('100%').layoutWeight(1)}
 }
 @Builder
 photoUI() {
  Row(){Button('返回').fontSize(13).backgroundColor('#222627').onClick(()=>this.navigate('Album'));Text('照片').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1);Button('删除').fontSize(13).backgroundColor('#222627').onClick(()=>{if(this.current)this.deletePhoto(this.current);})}.height(58).width('100%')
  Stack({alignContent:Alignment.TopStart}) {
   if(this.after){Image(this.after).objectFit(ImageFit.Contain).width('100%').height('100%').scale({x:this.zoom,y:this.zoom})}
   if(this.before){Stack(){Image(this.before).objectFit(ImageFit.Contain).width('100%').height('100%').scale({x:this.zoom,y:this.zoom})}
    .width('100%').height('100%').clipShape(new Rect({width:this.after?this.previewWidth*this.split/100:this.previewWidth,height:this.previewHeight}))}
   if(this.after) {
    Row(){Text('原片').fontSize(11).padding(8).backgroundColor('#B30F1112').borderRadius(8);Blank();Text('胶片').fontSize(11).padding(8).backgroundColor('#B30F1112').borderRadius(8)}.padding(10).width('100%').hitTestBehavior(HitTestMode.None)
    Stack(){Divider().vertical(true).height('100%').width(1).color('#EBEBE2');Text('‹ ›').fontSize(18).width(34).height(34).textAlign(TextAlign.Center).backgroundColor('#0F1112').borderRadius(17).border({width:1,color:'#EBEBE2'})}
     .width(48).height('100%').position({x:this.previewWidth*this.split/100-24,y:0})
     .gesture(PanGesture({direction:PanDirection.Horizontal}).onActionStart(()=>this.baseSplit=this.split).onActionUpdate((e:GestureEvent)=>this.split=Math.max(0,Math.min(100,this.baseSplit+e.offsetX/this.previewWidth*100))))
   }
  }.width('100%').layoutWeight(1).clip(true)
   .onAreaChange((_old:Area,area:Area)=>{this.previewWidth=Number(area.width);this.previewHeight=Number(area.height);})
   .gesture(PinchGesture().onActionStart(()=>this.zoomBase=this.zoom).onActionUpdate((e:GestureEvent)=>this.zoom=Math.max(1,Math.min(6,this.zoomBase*e.scale))))
  if(this.current?.state==='failed'){Text('冲洗未完成 · 点此重新冲洗').fontSize(12).fontColor('#B8C7A8').height(36).onClick(()=>{if(this.current)this.store!.retry(this.current.folder);})}
  else {Text(this.current?.developing()?'冲洗中 · 可先保存原片':'拖动竖线 · 原片与胶片').fontSize(12).fontColor('#949C9B').height(36)}
  if(this.saveSheet) {
   Column({space:12}) {
    Row({space:12}){Button('原片').layoutWeight(1).fontColor(this.saveKind==='原片'?'#0F1112':'#EBEBE2').backgroundColor(this.saveKind==='原片'?'#B8C7A8':'#222627').onClick(()=>this.saveKind='原片');Button('胶片').layoutWeight(1).fontColor(this.saveKind==='胶片'?'#0F1112':'#EBEBE2').backgroundColor(this.saveKind==='胶片'?'#B8C7A8':'#222627').enabled(!!this.after).onClick(()=>this.saveKind='胶片')}
    SaveButton({icon:SaveIconStyle.FULL_FILLED,text:SaveDescription.SAVE_IMAGE,buttonType:ButtonType.Capsule})
     .onClick((_event:ClickEvent,result:SaveButtonOnClickResult)=>{if(result===SaveButtonOnClickResult.SUCCESS)this.saveToGallery().catch((e:Error)=>this.error(e));})
    Button('取消').fontSize(13).backgroundColor('#222627').onClick(()=>this.saveSheet=false)
   }.padding(16).backgroundColor('#191C1D').borderRadius(14)
  } else {Button('保存图片').width('100%').height(48).fontColor('#0F1112').backgroundColor('#B8C7A8').onClick(()=>{this.saveKind=this.after?'胶片':'原片';this.saveSheet=true;})}
 }
 build() {
  Column() {
   if(this.page==='Camera'){this.cameraUI()}
   else if(this.page==='LUT') {
    Row(){Button('返回').fontSize(13).backgroundColor('#222627').onClick(()=>this.finishLut());Text('胶卷色调').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)}.height(58)
    LutWheel({films:this.films,initial:this.chosenIndex,onSelect:(index:number):void=>{this.chosenIndex=index;}}).layoutWeight(1)
    Text('上下拨动 · 自动吸附 · 箭头指向当前选择').fontSize(11).fontColor('#949C9B').height(40)
    Button('使用并返回拍摄').width('100%').height(48).fontColor('#0F1112').backgroundColor('#B8C7A8').onClick(()=>this.finishLut())
   } else if(this.page==='Album'){this.albumUI()}
   else if(this.page==='Photo'){this.photoUI()}
  }.width('100%').height('100%').padding({left:16,right:16,top:8,bottom:16}).backgroundColor('#0F1112').foregroundColor('#EBEBE2')
 }
}
