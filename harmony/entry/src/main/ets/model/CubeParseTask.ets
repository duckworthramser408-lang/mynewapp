import { CubeTable } from './CubeTable';
// Only serializable input/output crosses the TaskPool boundary. Parsing never blocks ArkUI.
@Concurrent
export function parseCubeTask(text: string): ArrayBuffer {
 const cube=CubeTable.parse(text);const packed=new Float32Array(7+cube.values.length);
 packed[0]=cube.size;packed.set(cube.minimum,1);packed.set(cube.maximum,4);packed.set(cube.values,7);return packed.buffer;
}
