#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* In-place RGBA8, sRGB encoded values. .cube order: red varies fastest, then
 * green, then blue. Trilinear interpolation, input clamped to DOMAIN_MIN/MAX,
 * output clamped to 8-bit; alpha and row padding preserved. Immutable table may
 * be shared between threads. Validation completes before modifying pixels. */
int g23_apply_lut(uint8_t *rgba, int width, int height, size_t stride, const float *table,
                  size_t table_count, int size, const float domain_min[3],
                  const float domain_max[3], char *error, size_t error_size);
/* Strength 0..1, in encoded sRGB. 0 preserves input exactly, 1 matches g23_apply_lut. */
int g23_apply_lut_strength(uint8_t *rgba, int width, int height, size_t stride, const float *table,
                           size_t table_count, int size, const float domain_min[3],
                           const float domain_max[3], float intensity, char *error,
                           size_t error_size);
#ifdef __cplusplus
}
#endif
