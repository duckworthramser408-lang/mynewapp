#!/usr/bin/env python3
"""Real offline model + shared effect checks. Public photos stay in ignored .build.
Derived glasses/occlusion/multi/large cases are explicitly labelled synthetic.
"""
from tools.beauty_reference import *
import resource,hashlib

def run():
    models=LocalModels();records=[]
    grace=cv2.cvtColor(cv2.imread(str(ROOT/'.build/beauty-research/grace.jpg')),cv2.COLOR_BGR2RGB)
    portrait=cv2.cvtColor(cv2.imread(str(ROOT/'.build/beauty-research/test_portrait')),cv2.COLOR_BGR2RGB)
    side=cv2.cvtColor(cv2.imread(str(ROOT/'.build/beauty-research/lena.jpg')),cv2.COLOR_BGR2RGB)
    noface=np.zeros((600,800,3),np.uint8)+90
    for x in range(20,800,40):cv2.line(noface,(x,0),(x,599),(130,140,150),2)
    real_face,_=models.detect(grace);assert real_face is not None
    glasses=grace.copy();points=real_face[0]*np.array([512,600,512])
    for outer,inner in [(33,133),(263,362)]:
        lo=np.minimum(points[outer,:2],points[inner,:2]).astype(int);hi=np.maximum(points[outer,:2],points[inner,:2]).astype(int)
        cv2.rectangle(glasses,tuple(lo-np.array([9,7])),tuple(hi+np.array([9,7])),(25,25,25),3)
    occluded=grace.copy();lo=np.floor(points[:,:2].min(0)).astype(int);hi=np.ceil(points[:,:2].max(0)).astype(int)
    cv2.rectangle(occluded,(lo[0],lo[1]),(hi[0],lo[1]+(hi[1]-lo[1])//3),(20,20,20),-1)
    multi=np.full((500,800,3),70,np.uint8)
    multi[70:430,30:354]=cv2.resize(grace[90:390,130:400],(324,360))
    multi[70:430,460:743]=cv2.resize(portrait[60:390,270:530],(283,360))
    # 12 MP stress, enlarged source, NOT a real high-resolution camera photo.
    large=cv2.resize(grace,(3200,3750))
    cases=[('front_real',grace),('smile_real',portrait),('side_real',side),('glasses_synthetic',glasses),('occluded_forehead_synthetic',occluded),('weaklight_synthetic',(grace*.12).astype('uint8')),('multi_synthetic',multi),('no_face_synthetic',noface),('12mp_enlarged_stress',large)]
    for name,image in cases:
        start=time.perf_counter();face,info=models.detect(image);detected=time.perf_counter();out,report=apply(image,face);end=time.perf_counter()
        assert out.shape==image.shape
        if face is None:assert np.array_equal(out,image)
        else:
            p=face[0];fw=np.linalg.norm((p[454,:2]-p[234,:2])*np.array(image.shape[1::-1]))
            assert report['max_displacement_px'] <= fw*.0081
            if len(face[1]):
                for box in face[1]:
                    x0=max(0,math.ceil(box[0]*image.shape[1]));x1=min(image.shape[1],math.floor(box[2]*image.shape[1]));y0=max(0,math.ceil(box[1]*image.shape[0]));y1=min(image.shape[0],math.floor(box[3]*image.shape[0]));assert np.array_equal(out[y0:y1,x0:x1],image[y0:y1,x0:x1])
            # Fixed nose tip and mouth centre survive both stages.
            for i in [1,13,14]:
                x,y=np.round(p[i,:2]*np.array(image.shape[1::-1])).astype(int);assert np.array_equal(out[max(0,y-1):y+2,max(0,x-1):x+2],image[max(0,y-1):y+2,max(0,x-1):x+2])
        if name in ('weaklight_synthetic','no_face_synthetic'):assert report['applied']==0
        if name=='front_real':assert report['applied']&1 and report['applied']&4
        if name=='multi_synthetic':assert face is not None and info['detections']==2 and len(face[1])==1
        filtered,lut_ms=filter_rgb(out)
        encode_start=time.perf_counter();ok,jpeg=cv2.imencode('.jpg',cv2.cvtColor(filtered,cv2.COLOR_RGB2BGR),[cv2.IMWRITE_JPEG_QUALITY,95]);encode_ms=(time.perf_counter()-encode_start)*1000
        assert ok and jpeg.size>0
        report.update(name=name,width=image.shape[1],height=image.shape[0],detect_ms=round((detected-start)*1000,2),effect_ms=round((end-detected)*1000,2),lut_ms=round(lut_ms,2),encode_ms=round(encode_ms,2),process_rss_highwater_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,**info)
        records.append(report);print(json.dumps(report),flush=True)
        cv2.imwrite(str(ROOT/'.build/beauty-research'/(name+'-check.png')),cv2.cvtColor(out,cv2.COLOR_RGB2BGR))
    # Independent legacy MediaPipe graph validates coordinate projection of identical weights.
    import mediapipe as mp
    with mp.solutions.face_mesh.FaceMesh(static_image_mode=True,max_num_faces=8) as reference:
        result=reference.process(grace);expected=np.array([[p.x,p.y,p.z] for p in result.multi_face_landmarks[0].landmark])
    error=float(np.sqrt(np.mean((expected[:,:2]-real_face[0][:,:2])**2)))
    assert error<.005,error
    cpu_face,cpu_info=LocalModels(accelerated=False).detect(grace)
    assert cpu_face is not None
    cpu_error=float(np.sqrt(np.mean((real_face[0][:,:2]-cpu_face[0][:,:2])**2)))
    assert cpu_error<.001,cpu_error
    # Baseline and edited use the same filter; input is never overwritten/reused as an edited source.
    output,report=apply(grace,real_face);again,r2=apply(grace,real_face);assert np.array_equal(output,again)
    # Crease-only algorithm actually changes image data when reliable contrast is present.
    face,_=models.detect(portrait);folds,fold_report=apply(portrait,face,Settings(0,1,0));assert fold_report['applied']&2
    changed=np.any(folds!=portrait,axis=2);assert changed.sum()>0 and changed.mean()<.05
    assert np.max(folds.astype('int16')-portrait)<12
    # Actual source pixels near the nose/mouth/eyes and outside candidate regions stay intact.
    cv2.imwrite(str(ROOT/'.build/beauty-research/fold-only-check.png'),cv2.cvtColor(folds,cv2.COLOR_RGB2BGR))
    cv2.imwrite(str(ROOT/'.build/beauty-research/fold-only-difference-x30.png'),cv2.cvtColor(np.clip(np.abs(folds.astype('int16')-portrait)*30,0,255).astype('uint8'),cv2.COLOR_RGB2BGR))
    result=dict(version='0.5',device_validation='unverified',platform='Linux host, ai-edge-litert 1.4.0, CPU XNNPACK, C++ -O2',cases=records,landmark_reference_rms_normalized=error,plain_cpu_rms_normalized=cpu_error,repeat_from_original_deterministic=True,fold_only_changed_pixels=int(changed.sum()),full_head_implemented=False)
    (ROOT/'.build/beauty-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result
if __name__=='__main__':run()
