export const render: (rgba: ArrayBuffer, width: number, height: number, document: string, cube?: ArrayBuffer, size?: number, minimum?: ArrayBuffer, maximum?: ArrayBuffer) => Promise<ArrayBuffer>;
export const validate: (document: string) => void;
// Direct sRGB LUT path. The fourth argument is reserved and must be an empty string.
export const develop: (rgba: ArrayBuffer, width: number, height: number, reserved: string, cube: ArrayBuffer, size: number, minimum: ArrayBuffer, maximum: ArrayBuffer) => Promise<ArrayBuffer>;
