#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Encoded sRGB RGBA8, orientation/crop already normalized. Landmarks: 468 xyz,
 * x/y normalized to output dimensions, z normalized to width (MediaPipe mesh).
 * Rectangle floats are normalized left/top/right/bottom for OTHER detections.
 * Values are controls, not a percentage of facial displacement. No head shrink.
 * Transactional: failure leaves rgba unchanged. Success may deliberately skip. */
typedef struct {
    float symmetry, folds, small_face;
} G23BeautySettings;
typedef struct {
    int applied; /* bit 1 symmetry, bit 2 crease, bit 4 small FACE */
    int reason;  /* 0 applied, 1 unreliable, 2 pose/expression, 3 edge/overlap, 4 dark */
    float yaw_degrees, pitch_degrees, pose_error, max_displacement_px, fold_confidence;
    double geometry_ms, folds_ms;
    size_t scratch_bytes;
    size_t changed_pixels;
} G23BeautyReport;
int g23_beautify(uint8_t *rgba, int width, int height, size_t stride, const float *landmarks,
                 size_t landmark_count, const float *other_rects, size_t other_count,
                 float presence, float flip_error, const G23BeautySettings *settings,
                 G23BeautyReport *report, char *error, size_t error_size);
#ifdef __cplusplus
}
#endif
