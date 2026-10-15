export class Document_parameters_hsl_red {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_orange {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_yellow {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_green {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_cyan {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_blue {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_purple {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl_magenta {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_hsl {
  red: Document_parameters_hsl_red = new Document_parameters_hsl_red();
  orange: Document_parameters_hsl_orange = new Document_parameters_hsl_orange();
  yellow: Document_parameters_hsl_yellow = new Document_parameters_hsl_yellow();
  green: Document_parameters_hsl_green = new Document_parameters_hsl_green();
  cyan: Document_parameters_hsl_cyan = new Document_parameters_hsl_cyan();
  blue: Document_parameters_hsl_blue = new Document_parameters_hsl_blue();
  purple: Document_parameters_hsl_purple = new Document_parameters_hsl_purple();
  magenta: Document_parameters_hsl_magenta = new Document_parameters_hsl_magenta();
}
export class Document_parameters_tone_curves {
  rgb: number[][] = [[0, 0], [1, 1]];
  red: number[][] = [[0, 0], [1, 1]];
  green: number[][] = [[0, 0], [1, 1]];
  blue: number[][] = [[0, 0], [1, 1]];
}
export class Document_parameters_color_grading_shadows {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_color_grading_midtones {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_color_grading_highlights {
  hue: number = 0;
  saturation: number = 0;
  luminance: number = 0;
}
export class Document_parameters_color_grading {
  shadows: Document_parameters_color_grading_shadows = new Document_parameters_color_grading_shadows();
  midtones: Document_parameters_color_grading_midtones = new Document_parameters_color_grading_midtones();
  highlights: Document_parameters_color_grading_highlights = new Document_parameters_color_grading_highlights();
  balance: number = 0;
  blending: number = 50;
}
export class Document_parameters_texture {
  sharpness: number = 0;
  clarity: number = 0;
  local_contrast: number = 0;
  grain_amount: number = 0;
  grain_size: number = 0;
  vignette: number = 0;
  fade: number = 0;
  grain_seed: number = 12345;
}
export class Document_parameters_geometry {
  crop: number[] | null = null;
  rotation_degrees: number = 0;
}
export class Document_parameters {
  exposure_ev: number = 0;
  contrast: number = 0;
  highlights: number = 0;
  shadows: number = 0;
  whites: number = 0;
  blacks: number = 0;
  temperature_shift: number = 0;
  tint: number = 0;
  vibrance: number = 0;
  saturation: number = 0;
  hsl: Document_parameters_hsl = new Document_parameters_hsl();
  tone_curves: Document_parameters_tone_curves = new Document_parameters_tone_curves();
  color_grading: Document_parameters_color_grading = new Document_parameters_color_grading();
  texture: Document_parameters_texture = new Document_parameters_texture();
  geometry: Document_parameters_geometry = new Document_parameters_geometry();
}
export class Document {
  schema_version: string = "1.0";
  render_pipeline_version: string = "1.0.0";
  processing_order: string[] = ["orientation_and_icc_to_srgb", "geometry", "srgb_to_linear", "white_balance_exposure", "basic_tone", "linear_to_srgb", "master_then_channel_curves", "hsl_vibrance_saturation", "color_grading", "fade", "sharpness_clarity_local_contrast", "grain_vignette", "srgb_rgba8_encode"];
  parameters: Document_parameters = new Document_parameters();
}
export class Scene {
 lighting: string[] = [];
 subjects: string[] = [];
 rating: number | null = null;
 notes: string = '';
 style_features: string = '';
 satisfied: boolean | null = null;
}
export class Capture {
 platform: string = 'HarmonyOS NEXT';
 device_brand: string = '';
 device_model: string = '';
 os_version: string = '';
 camera_id: string = '';
 captured_at: string = '';
 width: number = 0;
 height: number = 0;
 format: string = 'JPEG';
 iso: number | null = null;
 exposure_time_seconds: number | null = null;
 aperture: number | null = null;
 focal_length: number | null = null;
 equivalent_focal_length_35mm: number | null = null;
 white_balance: string | null = null;
}
export class FileHash {
 path: string = '';
 sha256: string = '';
 constructor(path: string, sha256: string) { this.path = path; this.sha256 = sha256; }
}
export class Metadata {
 schema_version: string = '1.0';
 sample_id: string = '';
 status: string = 'draft';
 style: string = 'G23_positive';
 capture: Capture = new Capture();
 scene: Scene = new Scene();
 sha256: Record<string, string> = {};
 render_pipeline_version: string = '1.0.0';
}
export class Control {
 path: string;
 min: number;
 max: number;
 step: number;
 get: (d: Document) => number;
 set: (d: Document, v: number) => void;
 constructor(path: string,min: number,max: number,step: number,get: (d: Document) => number,set: (d: Document,v: number) => void) {
  this.path=path;this.min=min;this.max=max;this.step=step;this.get=get;this.set=set;
 }
}
export function clone(d: Document): Document { return JSON.parse(JSON.stringify(d)) as Document; }
export function controls(): Control[] {
 return [
  new Control('exposure_ev', -5, 5, 0.1, (d: Document): number => d.parameters.exposure_ev, (d: Document, v: number): void => { d.parameters.exposure_ev=v; }),
  new Control('contrast', -100, 100, 1, (d: Document): number => d.parameters.contrast, (d: Document, v: number): void => { d.parameters.contrast=v; }),
  new Control('highlights', -100, 100, 1, (d: Document): number => d.parameters.highlights, (d: Document, v: number): void => { d.parameters.highlights=v; }),
  new Control('shadows', -100, 100, 1, (d: Document): number => d.parameters.shadows, (d: Document, v: number): void => { d.parameters.shadows=v; }),
  new Control('whites', -100, 100, 1, (d: Document): number => d.parameters.whites, (d: Document, v: number): void => { d.parameters.whites=v; }),
  new Control('blacks', -100, 100, 1, (d: Document): number => d.parameters.blacks, (d: Document, v: number): void => { d.parameters.blacks=v; }),
  new Control('temperature_shift', -100, 100, 1, (d: Document): number => d.parameters.temperature_shift, (d: Document, v: number): void => { d.parameters.temperature_shift=v; }),
  new Control('tint', -100, 100, 1, (d: Document): number => d.parameters.tint, (d: Document, v: number): void => { d.parameters.tint=v; }),
  new Control('vibrance', -100, 100, 1, (d: Document): number => d.parameters.vibrance, (d: Document, v: number): void => { d.parameters.vibrance=v; }),
  new Control('saturation', -100, 100, 1, (d: Document): number => d.parameters.saturation, (d: Document, v: number): void => { d.parameters.saturation=v; }),
  new Control('hsl.red.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.red.hue, (d: Document, v: number): void => { d.parameters.hsl.red.hue=v; }),
  new Control('hsl.red.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.red.saturation, (d: Document, v: number): void => { d.parameters.hsl.red.saturation=v; }),
  new Control('hsl.red.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.red.luminance, (d: Document, v: number): void => { d.parameters.hsl.red.luminance=v; }),
  new Control('hsl.orange.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.orange.hue, (d: Document, v: number): void => { d.parameters.hsl.orange.hue=v; }),
  new Control('hsl.orange.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.orange.saturation, (d: Document, v: number): void => { d.parameters.hsl.orange.saturation=v; }),
  new Control('hsl.orange.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.orange.luminance, (d: Document, v: number): void => { d.parameters.hsl.orange.luminance=v; }),
  new Control('hsl.yellow.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.yellow.hue, (d: Document, v: number): void => { d.parameters.hsl.yellow.hue=v; }),
  new Control('hsl.yellow.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.yellow.saturation, (d: Document, v: number): void => { d.parameters.hsl.yellow.saturation=v; }),
  new Control('hsl.yellow.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.yellow.luminance, (d: Document, v: number): void => { d.parameters.hsl.yellow.luminance=v; }),
  new Control('hsl.green.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.green.hue, (d: Document, v: number): void => { d.parameters.hsl.green.hue=v; }),
  new Control('hsl.green.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.green.saturation, (d: Document, v: number): void => { d.parameters.hsl.green.saturation=v; }),
  new Control('hsl.green.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.green.luminance, (d: Document, v: number): void => { d.parameters.hsl.green.luminance=v; }),
  new Control('hsl.cyan.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.cyan.hue, (d: Document, v: number): void => { d.parameters.hsl.cyan.hue=v; }),
  new Control('hsl.cyan.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.cyan.saturation, (d: Document, v: number): void => { d.parameters.hsl.cyan.saturation=v; }),
  new Control('hsl.cyan.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.cyan.luminance, (d: Document, v: number): void => { d.parameters.hsl.cyan.luminance=v; }),
  new Control('hsl.blue.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.blue.hue, (d: Document, v: number): void => { d.parameters.hsl.blue.hue=v; }),
  new Control('hsl.blue.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.blue.saturation, (d: Document, v: number): void => { d.parameters.hsl.blue.saturation=v; }),
  new Control('hsl.blue.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.blue.luminance, (d: Document, v: number): void => { d.parameters.hsl.blue.luminance=v; }),
  new Control('hsl.purple.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.purple.hue, (d: Document, v: number): void => { d.parameters.hsl.purple.hue=v; }),
  new Control('hsl.purple.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.purple.saturation, (d: Document, v: number): void => { d.parameters.hsl.purple.saturation=v; }),
  new Control('hsl.purple.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.purple.luminance, (d: Document, v: number): void => { d.parameters.hsl.purple.luminance=v; }),
  new Control('hsl.magenta.hue', -100, 100, 1, (d: Document): number => d.parameters.hsl.magenta.hue, (d: Document, v: number): void => { d.parameters.hsl.magenta.hue=v; }),
  new Control('hsl.magenta.saturation', -100, 100, 1, (d: Document): number => d.parameters.hsl.magenta.saturation, (d: Document, v: number): void => { d.parameters.hsl.magenta.saturation=v; }),
  new Control('hsl.magenta.luminance', -100, 100, 1, (d: Document): number => d.parameters.hsl.magenta.luminance, (d: Document, v: number): void => { d.parameters.hsl.magenta.luminance=v; }),
  new Control('color_grading.shadows.hue', 0, 360, 1, (d: Document): number => d.parameters.color_grading.shadows.hue, (d: Document, v: number): void => { d.parameters.color_grading.shadows.hue=v; }),
  new Control('color_grading.shadows.saturation', 0, 100, 1, (d: Document): number => d.parameters.color_grading.shadows.saturation, (d: Document, v: number): void => { d.parameters.color_grading.shadows.saturation=v; }),
  new Control('color_grading.shadows.luminance', -100, 100, 1, (d: Document): number => d.parameters.color_grading.shadows.luminance, (d: Document, v: number): void => { d.parameters.color_grading.shadows.luminance=v; }),
  new Control('color_grading.midtones.hue', 0, 360, 1, (d: Document): number => d.parameters.color_grading.midtones.hue, (d: Document, v: number): void => { d.parameters.color_grading.midtones.hue=v; }),
  new Control('color_grading.midtones.saturation', 0, 100, 1, (d: Document): number => d.parameters.color_grading.midtones.saturation, (d: Document, v: number): void => { d.parameters.color_grading.midtones.saturation=v; }),
  new Control('color_grading.midtones.luminance', -100, 100, 1, (d: Document): number => d.parameters.color_grading.midtones.luminance, (d: Document, v: number): void => { d.parameters.color_grading.midtones.luminance=v; }),
  new Control('color_grading.highlights.hue', 0, 360, 1, (d: Document): number => d.parameters.color_grading.highlights.hue, (d: Document, v: number): void => { d.parameters.color_grading.highlights.hue=v; }),
  new Control('color_grading.highlights.saturation', 0, 100, 1, (d: Document): number => d.parameters.color_grading.highlights.saturation, (d: Document, v: number): void => { d.parameters.color_grading.highlights.saturation=v; }),
  new Control('color_grading.highlights.luminance', -100, 100, 1, (d: Document): number => d.parameters.color_grading.highlights.luminance, (d: Document, v: number): void => { d.parameters.color_grading.highlights.luminance=v; }),
  new Control('color_grading.balance', -100, 100, 1, (d: Document): number => d.parameters.color_grading.balance, (d: Document, v: number): void => { d.parameters.color_grading.balance=v; }),
  new Control('color_grading.blending', 0, 100, 1, (d: Document): number => d.parameters.color_grading.blending, (d: Document, v: number): void => { d.parameters.color_grading.blending=v; }),
  new Control('texture.sharpness', 0, 100, 1, (d: Document): number => d.parameters.texture.sharpness, (d: Document, v: number): void => { d.parameters.texture.sharpness=v; }),
  new Control('texture.clarity', -100, 100, 1, (d: Document): number => d.parameters.texture.clarity, (d: Document, v: number): void => { d.parameters.texture.clarity=v; }),
  new Control('texture.local_contrast', -100, 100, 1, (d: Document): number => d.parameters.texture.local_contrast, (d: Document, v: number): void => { d.parameters.texture.local_contrast=v; }),
  new Control('texture.grain_amount', 0, 100, 1, (d: Document): number => d.parameters.texture.grain_amount, (d: Document, v: number): void => { d.parameters.texture.grain_amount=v; }),
  new Control('texture.grain_size', 0, 100, 1, (d: Document): number => d.parameters.texture.grain_size, (d: Document, v: number): void => { d.parameters.texture.grain_size=v; }),
  new Control('texture.vignette', -100, 100, 1, (d: Document): number => d.parameters.texture.vignette, (d: Document, v: number): void => { d.parameters.texture.vignette=v; }),
  new Control('texture.fade', 0, 100, 1, (d: Document): number => d.parameters.texture.fade, (d: Document, v: number): void => { d.parameters.texture.fade=v; }),
  new Control('texture.grain_seed', 0, 4294967295, 1, (d: Document): number => d.parameters.texture.grain_seed, (d: Document, v: number): void => { d.parameters.texture.grain_seed=v; })
 ];
}

export class HistoryState { states: string[] = []; cursor: number = 0; }
