import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
export default class EntryAbility extends UIAbility {
 onBackground(): void {this.context.eventHub.emit('g23.background');}
 onForeground(): void {this.context.eventHub.emit('g23.foreground');}
 onWindowStageCreate(stage: window.WindowStage): void {
  stage.loadContent('pages/Index').catch((e: Error) => console.error(e.message));
 }
}
