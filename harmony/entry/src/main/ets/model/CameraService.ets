import { camera } from '@kit.CameraKit';
import { image } from '@kit.ImageKit';
import { common, abilityAccessCtrl } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';

export class CameraService {
 private timer: number = -1;
 private manager?: camera.CameraManager;
 private input?: camera.CameraInput;
 private preview?: camera.PreviewOutput;
 private output?: camera.PhotoOutput;
 private session?: camera.PhotoSession;
 private saving: boolean = false;
 private finish?: (buffer: ArrayBuffer) => void;
 private failed?: (e: Error) => void;
 cameraId: string = '';
 capability: string = '';
 exposureRange: number[] = [];
 flashAvailable: boolean = false;
 async open(context: common.UIAbilityContext, surface: string, front: boolean): Promise<void> {
  const permission = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(context, ['ohos.permission.CAMERA']);
  if(permission.authResults[0] !== 0) throw new Error('请在系统设置允许相机权限，用于本机取景及拍照。');
  await this.close();
  try {
   this.manager=camera.getCameraManager(context);
   const devices=this.manager.getSupportedCameras();
   const device=devices.find((d: camera.CameraDevice) => d.cameraPosition === (front ? camera.CameraPosition.CAMERA_POSITION_FRONT : camera.CameraPosition.CAMERA_POSITION_BACK));
   if(!device) throw new Error('没有所选摄像头');
   this.cameraId=device.cameraId;
   const modes=this.manager.getSupportedSceneModes(device);
   if(!modes.includes(camera.SceneMode.NORMAL_PHOTO)) throw new Error('设备不支持标准拍照会话');
   const cap=this.manager.getSupportedOutputCapability(device,camera.SceneMode.NORMAL_PHOTO);
   const photos=cap.photoProfiles.filter((p: camera.Profile) => p.format===camera.CameraFormat.CAMERA_FORMAT_JPEG).sort((a: camera.Profile,b: camera.Profile) => b.size.width*b.size.height-a.size.width*a.size.height);
   if(photos.length===0 || cap.previewProfiles.length===0) throw new Error('没有经过确认的 JPEG / 预览输出');
   // Prefer <=24 MP; no silent downsampling of the preserved original.
   const photo=photos.find((p: camera.Profile) => p.size.width*p.size.height<=24000000) || photos[photos.length-1];
   const ratio=photo.size.width/photo.size.height;
   const previews=cap.previewProfiles.slice().sort((a: camera.Profile,b: camera.Profile) => Math.abs(a.size.width/a.size.height-ratio)-Math.abs(b.size.width/b.size.height-ratio));
   const preview=previews.find((p: camera.Profile) => p.size.width<=1920) || previews[0];
   this.input=this.manager.createCameraInput(device);await this.input.open();
   this.preview=this.manager.createPreviewOutput(preview,surface);
   this.output=this.manager.createPhotoOutput(photo);
   this.output.on('photoAvailable',(error: BusinessError,photo: camera.Photo): void => {
    if(error || !photo) { this.reject(new Error(`拍照失败 ${error?.code}`));return; }
    const main=photo.main;
    main.getComponent(image.ComponentType.JPEG,(e: BusinessError,component: image.Component): void => {
     try {if(e || !component?.byteBuffer) throw new Error('未收到 JPEG 数据');
      const copy=component.byteBuffer.slice(0);this.finish?.(copy);this.saving=false;if(this.timer>=0){clearTimeout(this.timer);this.timer=-1;}this.finish=undefined;this.failed=undefined;
     } catch(error) {this.reject(error as Error);} finally {main.release().catch((error: Error) => console.error(error.message));}
    });
   });
   this.session=this.manager.createSession(camera.SceneMode.NORMAL_PHOTO) as camera.PhotoSession;
   this.session.beginConfig();this.session.addInput(this.input);this.session.addOutput(this.preview);this.session.addOutput(this.output);
   await this.session.commitConfig();await this.session.start();
   this.flashAvailable=this.session.hasFlash();this.exposureRange=this.session.getExposureBiasRange();
   this.capability=`${this.cameraId}\nJPEG ${photo.size.width}×${photo.size.height} · 闪光 ${this.flashAvailable}\nRAW 未启用；第三方 API 不等同系统相机 HDR。`;
  } catch(e) { await this.close();throw e; }
 }
 private reject(e: Error): void {if(this.timer>=0){clearTimeout(this.timer);this.timer=-1;}this.saving=false;this.failed?.(e);this.finish=undefined;this.failed=undefined;}
 capture(): Promise<ArrayBuffer> {
  return new Promise((resolve,reject) => {
   if(this.saving || !this.output) {reject(new Error('相机忙或未就绪'));return;}
   this.saving=true;this.finish=resolve;this.failed=reject;this.timer=setTimeout(()=>this.reject(new Error('拍摄超时，请重试或切换 JPEG 摄像头')),30000);
   this.output.capture({quality:camera.QualityLevel.QUALITY_LEVEL_HIGH,rotation:camera.ImageRotation.ROTATION_0}).catch((e: Error) => this.reject(e));
  });
 }
 focus(x: number,y: number): void {
  if(this.session?.isFocusModeSupported(camera.FocusMode.FOCUS_MODE_AUTO)) {
   this.session.setFocusMode(camera.FocusMode.FOCUS_MODE_AUTO);
   this.session.setFocusPoint({x:Math.max(0,Math.min(1,x)),y:Math.max(0,Math.min(1,y))});
  } else throw new Error('当前摄像头不支持自动对焦');
 }
 setFlash(on: boolean): void {
  const mode=on ? camera.FlashMode.FLASH_MODE_OPEN : camera.FlashMode.FLASH_MODE_CLOSE;
  if(!this.session?.isFlashModeSupported(mode)) throw new Error('当前摄像头不支持该闪光模式');
  this.session.setFlashMode(mode);
 }
 async exposure(value: number): Promise<void> {
  if(!this.session || this.exposureRange.length===0) throw new Error('曝光补偿不可用');
  await this.session.setExposureBias(value);
 }
 async close(): Promise<void> {
  this.reject(new Error('相机会话已关闭'));
  const session=this.session;this.session=undefined;
  if(session){try{await session.stop();}catch(e){};await session.release();}
  if(this.output){await this.output.release();this.output=undefined;}
  if(this.preview){await this.preview.release();this.preview=undefined;}
  if(this.input){await this.input.close();this.input=undefined;}
 }
}
