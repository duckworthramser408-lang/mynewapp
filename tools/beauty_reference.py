"""Host reference of Android preprocessing; genuine local LiteRT models, shared C++ effect.
Not an Android device test. Run with tools/beauty-validation-requirements.txt environment.
"""
from pathlib import Path
import ctypes as C
import json,math,time
import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType
ROOT=Path(__file__).resolve().parents[1]
class Settings(C.Structure):_fields_=[('symmetry',C.c_float),('folds',C.c_float),('small_face',C.c_float)]
class Report(C.Structure):_fields_=[('applied',C.c_int),('reason',C.c_int),('yaw_degrees',C.c_float),('pitch_degrees',C.c_float),('pose_error',C.c_float),('max_displacement_px',C.c_float),('fold_confidence',C.c_float),('geometry_ms',C.c_double),('folds_ms',C.c_double),('scratch_bytes',C.c_size_t),('changed_pixels',C.c_size_t)]
def core():
    lib=C.CDLL(str(ROOT/'.build/libg23beauty.so'))
    lib.g23_beautify.argtypes=[C.c_void_p,C.c_int,C.c_int,C.c_size_t,C.c_void_p,C.c_size_t,C.c_void_p,C.c_size_t,C.c_float,C.c_float,C.POINTER(Settings),C.POINTER(Report),C.c_void_p,C.c_size_t]
    return lib
class LocalModels:
    def __init__(self, accelerated=True):
        options={} if accelerated else dict(experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
        self.det=Interpreter(model_path=str(ROOT/'models/beauty/face_detection_short_range.tflite'),num_threads=2,**options);self.det.allocate_tensors()
        self.mesh=Interpreter(model_path=str(ROOT/'models/beauty/face_landmark.tflite'),num_threads=2,**options);self.mesh.allocate_tensors()
        self.anchors=np.array([((x+.5)/grid,(y+.5)/grid) for grid,n in [(16,2),(8,6)] for y in range(grid) for x in range(grid) for _ in range(n)],dtype=np.float32)
        self.mirror=np.array(json.loads((ROOT/'models/beauty/mirror_indices.json').read_text()))
    def infer(self,model,input):
        model.set_tensor(model.get_input_details()[0]['index'],input[None].astype('float32'));model.invoke()
        return [model.get_tensor(t['index']) for t in model.get_output_details()]
    @staticmethod
    def sigmoid(x):return 1/(1+np.exp(-np.clip(x,-80,80)))
    @staticmethod
    def iou(a,b):
        lo=np.maximum(a[:2],b[:2]);hi=np.minimum(a[:2]+a[2:4],b[:2]+b[2:4]);area=np.prod(np.maximum(0,hi-lo));return area/max(1e-5,np.prod(a[2:4])+np.prod(b[2:4])-area)
    def detect(self,image):
        height,width=image.shape[:2];scale=min(1,960/max(width,height));small=cv2.resize(image,(max(1,round(width*scale)),max(1,round(height*scale))))
        h,w=small.shape[:2];s=min(128/w,128/h);dw,dh=w*s,h*s;px,py=(128-dw)/2,(128-dh)/2
        mat=np.array([[s,0,px],[0,s,py]],dtype=np.float32)
        tile=cv2.warpAffine(small,mat,(128,128),flags=cv2.INTER_LINEAR)
        raw,logits=self.infer(self.det,tile/127.5-1);raw=raw[0];scores=self.sigmoid(logits.ravel());rows=[]
        for i in np.flatnonzero(scores>=.45):
            cx=(raw[i,0]+self.anchors[i,0]*128-px)/dw;cy=(raw[i,1]+self.anchors[i,1]*128-py)/dh
            bw,bh=raw[i,2]/dw,raw[i,3]/dh
            eyes=raw[i,4:8].copy();eyes[::2]=(eyes[::2]+self.anchors[i,0]*128-px)/dw;eyes[1::2]=(eyes[1::2]+self.anchors[i,1]*128-py)/dh
            rows.append(np.r_[cx-bw/2,cy-bh/2,bw,bh,eyes,scores[i]])
        rows.sort(key=lambda b:-b[-1]);boxes=[]
        while rows and len(boxes)<9:
            top=rows[0];group=[r for r in rows if self.iou(top,r)>.3];rows=[r for r in rows if self.iou(top,r)<=.3]
            box=np.average(np.array(group),axis=0,weights=[r[-1] for r in group]);box[-1]=top[-1];boxes.append(box)
        boxes.sort(key=lambda b:-b[2]*b[3]);rejections=[]
        if len(boxes)>8:return None,{'detections':len(boxes),'rejections':['too_many']}
        for index,box in enumerate(boxes):
            if box[-1]<.70 or min(box[2]*width,box[3]*height)<80:continue
            angle=math.atan2((box[7]-box[5])*h,(box[6]-box[4])*w);side=max(box[2]*w,box[3]*h)*1.5;cs,sn=math.cos(angle),math.sin(angle);cx,cy=(box[0]+box[2]/2)*w,(box[1]+box[3]/2)*h
            to_image=np.array([[side/192*cs,-side/192*sn,cx-side*.5*(cs-sn)],[side/192*sn,side/192*cs,cy-side*.5*(sn+cs)]],dtype='float32')
            crop=cv2.warpAffine(small,cv2.invertAffineTransform(to_image),(192,192),flags=cv2.INTER_LINEAR)
            raw,pres=self.infer(self.mesh,crop/255);raw=raw.reshape(468,3);presence=float(self.sigmoid(pres.ravel()[0]));flipped,fpr=self.infer(self.mesh,np.ascontiguousarray(crop[:,::-1])/255)
            flipped=flipped.reshape(468,3);fpresence=float(self.sigmoid(fpr.ravel()[0]));stable=np.array([33,133,263,362,168,1,98,327,61,291,234,454,152,10,93,323])
            dx=(raw[stable,0]+flipped[self.mirror[stable],0])/192-1;dy=(raw[stable,1]-flipped[self.mirror[stable],1])/192
            consistency=float(np.sqrt(np.mean(dx*dx+dy*dy)))
            rejections.append(dict(score=float(box[-1]),presence=presence,flip_presence=fpresence,flip_error=consistency))
            if min(presence,fpresence)<.85 or consistency>.025:continue
            xy=raw[:,:2]@to_image[:,:2].T+to_image[:,2];points=np.c_[xy[:,0]/w,xy[:,1]/h,raw[:,2]/192*side/w].astype('float32')
            others=np.array([[b[0],b[1],b[0]+b[2],b[1]+b[3]] for i,b in enumerate(boxes) if i!=index],dtype='float32')
            return (points,others,min(presence,fpresence),consistency),dict(detections=len(boxes),rejections=rejections)
        return None,dict(detections=len(boxes),rejections=rejections)
def apply(image,face,settings=Settings(.2,.25,.1)):
    rgba=cv2.cvtColor(image,cv2.COLOR_RGB2RGBA);report=Report();error=C.create_string_buffer(256)
    if face is None:return image.copy(),{'applied':0,'reason':'no_reliable_face'}
    points,others,presence,flip_error=face
    code=core().g23_beautify(rgba.ctypes.data,rgba.shape[1],rgba.shape[0],rgba.strides[0],points.ctypes.data,468,others.ctypes.data,len(others),presence,flip_error,C.byref(settings),C.byref(report),error,256)
    if code:raise ValueError(error.value.decode())
    return rgba[:,:,:3].copy(),{k:getattr(report,k) for k,_ in Report._fields_}

def filter_rgb(image, strength=.75):
    from tools.import_luts import parse_cube
    catalog=json.loads((ROOT/'looks/luts/catalog.json').read_text())['luts']
    size,lo,hi,values=parse_cube((ROOT/'looks/luts'/catalog[0]['file']).read_text(encoding='utf-8-sig'))
    grid=np.asarray(values,dtype='float32');low=np.asarray(lo,dtype='float32');high=np.asarray(hi,dtype='float32')
    rgba=cv2.cvtColor(image,cv2.COLOR_RGB2RGBA);error=C.create_string_buffer(256);lib=core()
    function=lib.g23_apply_lut_strength
    function.argtypes=[C.c_void_p,C.c_int,C.c_int,C.c_size_t,C.c_void_p,C.c_size_t,C.c_int,C.c_void_p,C.c_void_p,C.c_float,C.c_void_p,C.c_size_t]
    start=time.perf_counter()
    assert function(rgba.ctypes.data,rgba.shape[1],rgba.shape[0],rgba.strides[0],grid.ctypes.data,grid.size,size,low.ctypes.data,high.ctypes.data,strength,error,256)==0,error.value
    return rgba[:,:,:3].copy(),(time.perf_counter()-start)*1000
if __name__=='__main__':
    models=LocalModels();results=[]
    for name in ('astronaut.png','test_portrait','grace.jpg','lena.jpg','messi.jpg','faces.jpg'):
        image=cv2.cvtColor(cv2.imread(str(ROOT/'.build/beauty-research'/name)),cv2.COLOR_BGR2RGB);start=time.perf_counter();face,info=models.detect(image);detected=time.perf_counter();output,report=apply(image,face)
        result=dict(image=name,width=image.shape[1],height=image.shape[0],detect_ms=round((detected-start)*1000,2),**info,**report);print(json.dumps(result));results.append(result)
        if face is not None:np.savez(ROOT/'.build/beauty-research'/(name+'.npz'),landmarks=face[0],others=face[1],presence=face[2],flip_error=face[3])
        cv2.imwrite(str(ROOT/'.build/beauty-research'/(name+'-natural.png')),cv2.cvtColor(output,cv2.COLOR_RGB2BGR))
    (ROOT/'.build/beauty-research/real-inference.json').write_text(json.dumps(results,indent=2))
