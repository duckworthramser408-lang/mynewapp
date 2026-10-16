import { FilmLut } from '../model/LutLibrary';
import { vibrator } from '@kit.SensorServiceKit';

@Component
export struct LutWheel {
 @Prop films: FilmLut[] = [];
 @Prop initialIndex: number = 0;
 onSelect: (index: number)=>void = (_index: number): void=>{};
 @State position: number = 0;
 private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
 private ready: boolean = false;
 private w: number = 1; private h: number = 1; private timer: number = -1;
 private down: number = 0; private last: number = 0; private time: number = 0; private velocity: number = 0; private dragged: boolean = false; private lastTick: number = 0;private reported: number = 0;
 private index(slot: number): number {return ((slot%this.films.length)+this.films.length)%this.films.length;}
 aboutToAppear(): void {this.position=this.initialIndex;this.reported=this.initialIndex;}
 aboutToDisappear(): void {if(this.timer>=0)clearInterval(this.timer);}
 private move(value: number): void {
  this.position=value;const index=this.index(Math.round(value));if(index!==this.reported){this.reported=index;this.onSelect(index);const now=Date.now();if(now-this.lastTick>=45){this.lastTick=now;vibrator.startVibration({type:'time',duration:8},{usage:'touch'}).catch((_e:Error)=>{});}}
  this.draw();
 }
 private snap(target: number): void {
  if(this.timer>=0)clearInterval(this.timer);const from=this.position;const start=Date.now();
  this.timer=setInterval(()=>{const t=Math.min(1,(Date.now()-start)/360);const q=t-1;this.move(from+(target-from)*(1+2.55*q*q*q+.55*q*q));if(t===1){clearInterval(this.timer);this.timer=-1;this.move(target);}},16);
 }
 private cylinder(x: number,y: number,index: number,focus: number,alpha: number): void {
  const c=this.canvas;c.save();c.translate(x,y);const scale=.82+.18*focus;c.scale(scale,scale);c.globalAlpha=alpha;
  c.fillStyle='#1B1E1D';c.fillRect(-7,-49,14,20);const gradient=c.createLinearGradient(-29,0,29,0);gradient.addColorStop(0,'#121514');gradient.addColorStop(.25,'#424943');gradient.addColorStop(.65,'#202622');gradient.addColorStop(1,'#111412');c.fillStyle=gradient;c.fillRect(-29,-35,58,72);c.beginPath();c.ellipse(0,37,29,10,0,0,Math.PI*2);c.fill();
  c.fillStyle=this.films[index].color;c.fillRect(-28,-13,56,38);c.beginPath();c.ellipse(0,25,28,8,0,0,Math.PI*2);c.fill();
  c.fillStyle='#191E1A';c.beginPath();c.ellipse(0,-35,29,10,0,0,Math.PI*2);c.fill();c.strokeStyle=focus>.5?this.films[index].color:'#687168';c.lineWidth=1;c.stroke();
  c.fillStyle='#1B211B';c.font='10px sans-serif';c.textAlign='center';c.fillText('FILM',0,3);c.font='8px sans-serif';c.fillText((index+1).toString().padStart(2,'0'),0,16);c.restore();
 }
 private draw(): void {
  if(!this.ready||!this.films.length)return;const c=this.canvas;const r=Math.min(this.w*.64,this.h*.4);const cx=this.w*.96;const cy=this.h*.5;c.clearRect(0,0,this.w,this.h);
  c.strokeStyle='#313636';c.lineWidth=1;c.beginPath();c.arc(cx,cy,r,Math.PI/2,Math.PI*1.5);c.stroke();
  const base=Math.floor(this.position);
  for(let i=-32;i<=32;i++){const a=(i/8-(this.position-base))*.52;if(Math.abs(a)>1.6)continue;const outer=r+10,inner=outer-(i%8===0?9:4);c.beginPath();c.moveTo(cx-Math.cos(a)*outer,cy+Math.sin(a)*outer);c.lineTo(cx-Math.cos(a)*inner,cy+Math.sin(a)*inner);c.stroke();}
  const slots: number[]=[];for(let i=base-4;i<=base+4;i++)slots.push(i);slots.sort((a:number,b:number)=>Math.abs(b-this.position)-Math.abs(a-this.position));
  for(const slot of slots){const delta=slot-this.position;const a=delta*.52;if(Math.abs(a)>1.65)continue;this.cylinder(cx-r*Math.cos(a),cy+r*Math.sin(a),this.index(slot),Math.max(0,1-Math.abs(delta)),Math.max(.25,.38+.62*(1-Math.abs(a)/1.8)));}
  const tip=Math.max(38,cx-r-39);c.strokeStyle='#B8C7A8';c.lineWidth=1.7;c.beginPath();c.moveTo(12,cy);c.lineTo(tip,cy);c.moveTo(tip-7,cy-5);c.lineTo(tip,cy);c.lineTo(tip-7,cy+5);c.stroke();
  c.textAlign='center';c.fillStyle='#949C9B';c.font='10px sans-serif';const x=this.w*.77;c.fillText('当前胶卷',x,cy-39);
  c.font='19px sans-serif';c.fillStyle='#EBEBE2';const name=this.films[this.index(Math.round(this.position))].name;let line='';let row=0;
  for(const letter of name){if(line.length&&c.measureText(line+letter).width>this.w*.39){c.fillText(line,x,cy-5+row*26);line='';row++;}line+=letter;}c.fillText(line,x,cy-5+row*26);
  c.font='10px sans-serif';c.fillStyle='#949C9B';c.fillText(`${this.index(Math.round(this.position))+1} / ${this.films.length}`,x,cy+Math.max(48,row*26+25));
 }
 build() {
  Canvas(this.canvas).width('100%').height('100%').onReady(()=>{this.ready=true;this.draw();}).onAreaChange((_old:Area,area:Area)=>{this.w=Number(area.width);this.h=Number(area.height);this.draw();})
   .accessibilityText(this.films.length?'当前胶卷：'+this.films[this.index(Math.round(this.position))].name:'胶卷选择')
   .onTouch((event:TouchEvent)=>{if(!event.changedTouches.length)return;const touch=event.changedTouches[0];const now=Date.now();
    if(event.type===TouchType.Down){if(this.timer>=0)clearInterval(this.timer);this.timer=-1;this.down=touch.y;this.last=touch.y;this.time=now;this.velocity=0;this.dragged=false;}
    else if(event.type===TouchType.Move){if(Math.abs(touch.y-this.down)>6)this.dragged=true;const spacing=Math.max(40,Math.min(this.w*.64,this.h*.4)*.5);if(this.dragged)this.move(this.position-(touch.y-this.last)/spacing);this.velocity=-(touch.y-this.last)/spacing/Math.max(.016,(now-this.time)/1000);this.last=touch.y;this.time=now;}
    else if(event.type===TouchType.Up||event.type===TouchType.Cancel){let target=Math.round(this.position+Math.max(-2.5,Math.min(2.5,this.velocity*.12)));if(!this.dragged){const r=Math.min(this.w*.64,this.h*.4);let distance=60;for(let i=Math.floor(this.position)-3;i<=Math.floor(this.position)+3;i++){const a=(i-this.position)*.52;const d=Math.hypot(touch.x-(this.w*.96-r*Math.cos(a)),touch.y-(this.h*.5+r*Math.sin(a)));if(d<distance){distance=d;target=i;}}}this.snap(target);}
   })
 }
}
