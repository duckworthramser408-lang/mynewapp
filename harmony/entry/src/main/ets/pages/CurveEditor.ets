@Component
export struct CurveEditor {
 @Prop @Watch('redraw') points: number[][] = [[0,0],[1,1]];
 onEdit: (points: number[][]) => void = (_points: number[][]): void => {};
 private settings: RenderingContextSettings = new RenderingContextSettings(true);
 private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
 private w: number = 1;
 private h: number = 1;
 private active: number = -1;
 private timer: number = -1;
 private startX: number = 0;
 private startY: number = 0;
 private ready: boolean = false;
 private cancelTimer(): void {if(this.timer>=0){clearTimeout(this.timer);this.timer=-1;}}
 aboutToDisappear(): void {this.cancelTimer();}
 private redraw(): void {
  if(!this.ready)return;
  const c=this.canvas;c.clearRect(0,0,this.w,this.h);c.fillStyle='#1c1e1c';c.fillRect(0,0,this.w,this.h);
  c.strokeStyle='#404440';c.lineWidth=1;
  for(let i=0;i<=4;i++){c.beginPath();c.moveTo(i*this.w/4,0);c.lineTo(i*this.w/4,this.h);c.moveTo(0,i*this.h/4);c.lineTo(this.w,i*this.h/4);c.stroke();}
  const n=this.points.length;const d: number[]=[];const h: number[]=[];const m: number[]=[];
  for(let i=0;i<n-1;i++){h.push(this.points[i+1][0]-this.points[i][0]);d.push((this.points[i+1][1]-this.points[i][1])/h[i]);}
  m.push(d[0]);for(let i=1;i<n-1;i++){const w1=2*h[i]+h[i-1],w2=h[i]+2*h[i-1];m.push(d[i-1]*d[i]<=0?0:(w1+w2)/(w1/d[i-1]+w2/d[i]));}m.push(d[n-2]);
  c.strokeStyle='#adbb9d';c.lineWidth=2;c.beginPath();c.moveTo(0,(1-this.points[0][1])*this.h);
  for(let i=0;i<n-1;i++){c.bezierCurveTo((this.points[i][0]+h[i]/3)*this.w,(1-this.points[i][1]-m[i]*h[i]/3)*this.h,(this.points[i+1][0]-h[i]/3)*this.w,(1-this.points[i+1][1]+m[i+1]*h[i]/3)*this.h,this.points[i+1][0]*this.w,(1-this.points[i+1][1])*this.h);}c.stroke();
  c.fillStyle='#d9e3d1';this.points.forEach((p: number[])=>{c.beginPath();c.arc(p[0]*this.w,(1-p[1])*this.h,5,0,Math.PI*2);c.fill();});
 }
 private touch(e: TouchEvent): void {
  if(e.changedTouches.length===0)return;
  const px=e.changedTouches[0].x,py=e.changedTouches[0].y;
  const x=Math.max(0,Math.min(1,px/this.w)),y=Math.max(0,Math.min(1,1-py/this.h));
  if(e.type===TouchType.Down){
   this.active=this.points.findIndex((p:number[])=>Math.abs(p[0]-x)<.06&&Math.abs(p[1]-y)<.12);
   if(this.active<0&&this.points.length<32&&x>.0001&&x<.9999){this.points.push([x,y]);this.points.sort((a:number[],b:number[])=>a[0]-b[0]);this.active=this.points.findIndex((p:number[])=>p[0]===x);}
   this.startX=px;this.startY=py;
   if(this.active>0&&this.active<this.points.length-1)this.timer=setTimeout(()=>{this.points.splice(this.active,1);this.active=-1;this.onEdit(this.points.map((p:number[])=>p.slice()));this.redraw();},700);
  } else if(e.type===TouchType.Move&&this.active>=0){
   if(Math.abs(px-this.startX)+Math.abs(py-this.startY)>5)this.cancelTimer();
   const point=this.points[this.active];point[1]=y;
   if(this.active>0&&this.active<this.points.length-1)point[0]=Math.max(this.points[this.active-1][0]+.0001,Math.min(this.points[this.active+1][0]-.0001,x));
  } else if(e.type===TouchType.Up){this.cancelTimer();this.active=-1;this.onEdit(this.points.map((p:number[])=>p.slice()));}
  else if(e.type===TouchType.Cancel){this.cancelTimer();this.active=-1;}
  this.redraw();
 }
 build(){Canvas(this.canvas).width('100%').height(160).onReady(()=>{this.ready=true;this.redraw();}).onAreaChange((_old:Area,a:Area)=>{this.w=Number(a.width);this.h=Number(a.height);this.redraw();}).onTouch((e:TouchEvent)=>this.touch(e))}
}
