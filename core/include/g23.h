#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* RGBA8, unpremultiplied, normalized orientation, SDR sRGB only. No aliasing.
 * Cropping/rotation and color-managed decode are adapter responsibilities.
 * Returns 0 on success, 1 invalid data, 2 allocation failure. Thread safe. */
int g23_render(const uint8_t *input, uint8_t *output, int width, int height,
               const char *parameters_json, char *error, size_t error_size);
const char *g23_pipeline_version(void);
#ifdef __cplusplus
}
#endif
