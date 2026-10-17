#include "beauty_canonical.h"
#include "g23_beauty.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>
namespace {
using Clock = std::chrono::steady_clock;
float clamp(float v, float lo = 0, float hi = 1) { return std::clamp(v, lo, hi); }
struct V {
    float x = 0, y = 0, z = 0;
    V operator+(V b) const { return {x + b.x, y + b.y, z + b.z}; }
    V operator-(V b) const { return {x - b.x, y - b.y, z - b.z}; }
    V operator*(float s) const { return {x * s, y * s, z * s}; }
};
float dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
float length(V a) { return std::sqrt(dot(a, a)); }
struct Pose {
    float r[3][3]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    float scale = 1, error = 1;
    V a, b;
    V rotate(V v) const {
        return {r[0][0] * v.x + r[0][1] * v.y + r[0][2] * v.z,
                r[1][0] * v.x + r[1][1] * v.y + r[1][2] * v.z,
                r[2][0] * v.x + r[2][1] * v.y + r[2][2] * v.z};
    }
    V unpose(V v) const {
        v = (v - b) * (1 / scale);
        return {r[0][0] * v.x + r[1][0] * v.y + r[2][0] * v.z + a.x,
                r[0][1] * v.x + r[1][1] * v.y + r[2][1] * v.z + a.y,
                r[0][2] * v.x + r[1][2] * v.y + r[2][2] * v.z + a.z};
    }
};
V canonical(int i) {
    return {kBeautyCanonical[i][0], kBeautyCanonical[i][1], kBeautyCanonical[i][2]};
}
Pose fit(const std::array<V, 468> &p, float fw) {
    constexpr int ids[] = {33, 133, 263, 362, 168, 6, 197, 195, 4, 234, 454, 127, 356, 152, 10, 1};
    Pose out;
    for (int i : ids) {
        out.a = out.a + canonical(i);
        out.b = out.b + p[i];
    }
    constexpr float count = sizeof(ids) / sizeof(int);
    out.a = out.a * (1 / count);
    out.b = out.b * (1 / count);
    double h[3][3]{};
    float norm = 0;
    for (int i : ids) {
        V a = canonical(i) - out.a, b = p[i] - out.b;
        float x[] = {a.x, a.y, a.z}, y[] = {b.x, b.y, b.z};
        norm += dot(a, a);
        for (int j = 0; j < 3; j++)
            for (int k = 0; k < 3; k++)
                h[j][k] += x[j] * y[k];
    }
    const double xx = h[0][0], xy = h[0][1], xz = h[0][2], yx = h[1][0], yy = h[1][1], yz = h[1][2],
                 zx = h[2][0], zy = h[2][1], zz = h[2][2];
    double n[4][4] = {{xx + yy + zz, yz - zy, zx - xz, xy - yx},
                      {yz - zy, xx - yy - zz, xy + yx, zx + xz},
                      {zx - xz, xy + yx, -xx + yy - zz, yz + zy},
                      {xy - yx, zx + xz, yz + zy, -xx - yy + zz}},
           vec[4][4]{};
    for (int i = 0; i < 4; i++)
        vec[i][i] = 1;
    // Symmetric Jacobi eigensolver; no external linear algebra/runtime dependency.
    for (int iteration = 0; iteration < 48; iteration++) {
        int a = 0, b = 1;
        for (int j = 0; j < 4; j++)
            for (int k = j + 1; k < 4; k++)
                if (std::abs(n[j][k]) > std::abs(n[a][b])) {
                    a = j;
                    b = k;
                }
        if (std::abs(n[a][b]) < 1e-10)
            break;
        double angle = .5 * std::atan2(2 * n[a][b], n[b][b] - n[a][a]), c = std::cos(angle),
               s = std::sin(angle);
        for (int j = 0; j < 4; j++) {
            double u = n[j][a], v = n[j][b];
            n[j][a] = c * u - s * v;
            n[j][b] = s * u + c * v;
        }
        for (int j = 0; j < 4; j++) {
            double u = n[a][j], v = n[b][j];
            n[a][j] = c * u - s * v;
            n[b][j] = s * u + c * v;
            double q = vec[j][a], r = vec[j][b];
            vec[j][a] = c * q - s * r;
            vec[j][b] = s * q + c * r;
        }
    }
    int best = 0;
    for (int i = 1; i < 4; i++)
        if (n[i][i] > n[best][best])
            best = i;
    float w = vec[0][best], x = vec[1][best], y = vec[2][best], z = vec[3][best];
    float r[3][3] = {{1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                     {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                     {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    std::memcpy(out.r, r, sizeof(r));
    float numerator = 0;
    for (int i : ids)
        numerator += dot(out.rotate(canonical(i) - out.a), p[i] - out.b);
    out.scale = std::max(.001f, numerator / std::max(.001f, norm));
    float error = 0;
    for (int i : ids) {
        V delta = out.rotate(canonical(i) - out.a) * out.scale + out.b - p[i];
        error += dot(delta, delta);
    }
    out.error = std::sqrt(error / count) / fw;
    return out;
}
struct Anchor {
    V p, d;
    float radius;
    int flag;
};
float linear(uint8_t v) {
    float c = v / 255.f;
    return c <= .04045f ? c / 12.92f : std::pow((c + .055f) / 1.055f, 2.4f);
}
uint8_t encoded(float v) {
    v = clamp(v);
    return uint8_t(
        std::lround(255 * (v <= .0031308f ? 12.92f * v : 1.055f * std::pow(v, 1 / 2.4f) - .055f)));
}
} // namespace
extern "C" int g23_beautify(uint8_t *rgba, int w, int h, size_t stride, const float *lm,
                            size_t count, const float *others, size_t otherCount, float presence,
                            float flipError, const G23BeautySettings *settings,
                            G23BeautyReport *report, char *error, size_t errorSize) {
    auto fail = [&](const char *s) {
        if (error && errorSize)
            std::snprintf(error, errorSize, "%s", s);
        return 1;
    };
    if (!report)
        return fail("缺少美颜状态输出");
    *report = {};
    report->reason = 1;
    if (!rgba || !lm || count != 468 || !settings || w <= 0 || h <= 0 ||
        int64_t(w) * h > 100000000 || stride < size_t(w) * 4 ||
        size_t(h) > std::numeric_limits<size_t>::max() / stride || otherCount > 64 ||
        (otherCount && !others))
        return fail("美颜输入无效");
    float controls[] = {settings->symmetry, settings->folds, settings->small_face, presence,
                        flipError};
    for (float c : controls)
        if (!std::isfinite(c) || c < 0 || c > 1)
            return fail("美颜强度或可靠性无效");
    for (size_t i = 0; i < otherCount * 4; i++)
        if (!std::isfinite(others[i]) || others[i] < -1 || others[i] > 2)
            return fail("其他人脸边界无效");
    std::array<V, 468> p;
    float minx = w, miny = h, maxx = 0, maxy = 0;
    for (size_t i = 0; i < count; i++) {
        for (int j = 0; j < 3; j++)
            if (!std::isfinite(lm[i * 3 + j]) || std::abs(lm[i * 3 + j]) > 2)
                return fail("人脸关键点无效");
        p[i] = {lm[i * 3] * w, lm[i * 3 + 1] * h, lm[i * 3 + 2] * w};
        minx = std::min(minx, p[i].x);
        maxx = std::max(maxx, p[i].x);
        miny = std::min(miny, p[i].y);
        maxy = std::max(maxy, p[i].y);
    }
    const float fw = std::hypot(p[454].x - p[234].x, p[454].y - p[234].y);
    if (fw < 64 || presence < .85f || flipError > .025f)
        return 0;
    const Pose pose = fit(p, fw);
    constexpr float degrees = 57.2957795f;
    const float yaw = std::asin(clamp(-pose.r[2][0], -1, 1)) * degrees,
                pitch = std::atan2(pose.r[2][1], pose.r[2][2]) * degrees;
    report->yaw_degrees = yaw;
    report->pitch_degrees = pitch;
    report->pose_error = pose.error;
    if (pose.error > .085f)
        return 0;
    float quality = clamp((.025f - flipError) / .015f) * clamp((.085f - pose.error) / .04f);
    float poseGate = clamp((25 - std::abs(yaw)) / 12) * clamp((22 - std::abs(pitch)) / 12);
    float opening = length(p[13] - p[14]) / fw, smile = length(p[61] - p[291]) / fw;
    float expression = clamp((.07f - opening) / .04f) * clamp((.48f - smile) / .09f);
    float geoGate = quality * poseGate * expression;
    report->reason = geoGate < .05f ? 2 : 0;
    const bool edge = minx < fw * .05f || miny < fw * .05f || maxx > w - 1 - fw * .05f ||
                      maxy > h - 1 - fw * .05f;
    bool overlap = false;
    for (size_t i = 0; i < otherCount; i++) {
        const float *b = others + i * 4;
        if (std::min(maxx, b[2] * w) - std::max(minx, b[0] * w) > 0 &&
            std::min(maxy, b[3] * h) - std::max(miny, b[1] * h) > 0)
            overlap = true;
    }
    if (edge || overlap) {
        geoGate = 0;
        report->reason = 3;
    }
    if (overlap)
        return 0;
    if (std::abs(yaw) > 32 || std::abs(pitch) > 30) {
        report->reason = 2;
        return 0;
    }
    float lut[256];
    for (int i = 0; i < 256; i++)
        lut[i] = linear(uint8_t(i));
    auto originalLuma = [&](V v) {
        int x = std::clamp(int(v.x), 0, w - 1), y = std::clamp(int(v.y), 0, h - 1);
        const uint8_t *b = rgba + size_t(y) * stride + x * 4;
        return .2126f * lut[b[0]] + .7152f * lut[b[1]] + .0722f * lut[b[2]];
    };
    float light = 0;
    for (int i : {205, 425, 50, 280, 4})
        light += originalLuma(p[i]);
    light /= 5;
    if (light < .015f) {
        report->reason = 4;
        return 0;
    }
    quality *= clamp((light - .015f) / .04f);
    try {
        std::vector<Anchor> anchors;
        constexpr int pairs[][2] = {{234, 454}, {93, 323},  {132, 361}, {58, 288},
                                    {172, 397}, {136, 365}, {150, 379}, {176, 400}};
        for (const auto &pair : pairs) {
            int a = pair[0], b = pair[1];
            V left = pose.unpose(p[a]), right = pose.unpose(p[b]);
            float midY = (left.y + right.y) * .5f, halfX = (right.x - left.x) * .5f;
            V targets[2] = {{-halfX, midY, left.z}, {halfX, midY, right.z}};
            int ids[] = {a, b};
            V locals[] = {left, right};
            for (int side = 0; side < 2; side++) {
                V raw = pose.rotate(targets[side] - locals[side]) * pose.scale;
                float norm = std::hypot(raw.x, raw.y);
                V delta;
                if (norm < fw * .05f) {
                    delta = raw * (settings->symmetry * .18f * geoGate);
                    delta.z = 0;
                    float amount = std::hypot(delta.x, delta.y);
                    if (amount > fw * .005f)
                        delta = delta * (fw * .005f / amount);
                    if (length(delta) > .001f)
                        anchors.push_back({p[ids[side]], delta, fw * .115f, 1});
                }
                if (a != 234 && a != 176 && settings->small_face > 0 && geoGate > 0) {
                    V inward = pose.rotate({side == 0 ? 1.f : -1.f, 0, 0}) *
                               (fw * .008f * settings->small_face * geoGate);
                    inward.z = 0;
                    if (length(inward) > .001f)
                        anchors.push_back({p[ids[side]], inward, fw * .12f, 4});
                }
            }
        }
        const int x0 = std::max(0, int(std::floor(minx - fw * .16f))),
                  y0 = std::max(0, int(std::floor(miny - fw * .06f))),
                  x1 = std::min(w, int(std::ceil(maxx + fw * .16f))),
                  y1 = std::min(h, int(std::ceil(maxy + fw * .06f)));
        const int rw = x1 - x0, rh = y1 - y0;
        if (rw <= 0 || rh <= 0)
            return 0;
        const auto geometryStart = Clock::now();
        std::vector<uint8_t> scratch(size_t(rw) * rh * 4);
        auto protectedPixel = [&](float x, float y) {
            for (size_t i = 0; i < otherCount; i++) {
                const float *b = others + i * 4;
                if (x > b[0] * w - fw * .04f && x < b[2] * w + fw * .04f &&
                    y > b[1] * h - fw * .04f && y < b[3] * h + fw * .04f)
                    return true;
            }
            for (const auto &eye : {std::pair<int, int>{33, 133}, {263, 362}}) {
                V centre = (p[eye.first] + p[eye.second]) * .5f;
                V axis = p[eye.second] - p[eye.first];
                float norm = std::hypot(axis.x, axis.y);
                if (norm > 0) {
                    float ex = ((x - centre.x) * axis.x + (y - centre.y) * axis.y) / norm;
                    float ey = (-(x - centre.x) * axis.y + (y - centre.y) * axis.x) / norm;
                    float rx = norm * .5f + fw * .055f, ry = fw * .085f;
                    if (ex * ex / (rx * rx) + ey * ey / (ry * ry) < 1)
                        return true;
                }
            }
            // Keep eyes/glasses, nose and lips fixed. Only lower cheek/contour anchors move.
            for (int id : {33, 133, 263, 362, 1, 98, 327, 61, 291, 13, 14}) {
                float radius = (id == 1 ? .065f : (id == 98 || id == 327) ? .035f : .05f) * fw;
                if ((x - p[id].x) * (x - p[id].x) + (y - p[id].y) * (y - p[id].y) < radius * radius)
                    return true;
            }
            return false;
        };
        auto softProtection = [&](float x, float y) {
            if (protectedPixel(x, y))
                return 0.f;
            float allowance = 1;
            auto fade = [&](float ratio) {
                float t = clamp((ratio - 1) / .35f);
                allowance = std::min(allowance, t * t * (3 - 2 * t));
            };
            for (int id : {33, 133, 263, 362, 1, 98, 327, 61, 291, 13, 14}) {
                float radius = (id == 1 ? .065f : (id == 98 || id == 327) ? .035f : .05f) * fw;
                fade(std::hypot(x - p[id].x, y - p[id].y) / radius);
            }
            for (const auto &eye : {std::pair<int, int>{33, 133}, {263, 362}}) {
                V centre = (p[eye.first] + p[eye.second]) * .5f,
                  axis = p[eye.second] - p[eye.first];
                float norm = std::hypot(axis.x, axis.y);
                if (norm > 0) {
                    float ex = ((x - centre.x) * axis.x + (y - centre.y) * axis.y) / norm,
                          ey = (-(x - centre.x) * axis.y + (y - centre.y) * axis.x) / norm;
                    float rx = norm * .5f + fw * .055f, ry = fw * .085f;
                    fade(std::sqrt(ex * ex / (rx * rx) + ey * ey / (ry * ry)));
                }
            }
            for (size_t i = 0; i < otherCount; i++) {
                const float *b = others + i * 4;
                float dx = std::max({b[0] * w - fw * .04f - x, 0.f, x - (b[2] * w + fw * .04f)}),
                      dy = std::max({b[1] * h - fw * .04f - y, 0.f, y - (b[3] * h + fw * .04f)});
                float t = clamp(std::hypot(dx, dy) / (fw * .04f));
                allowance = std::min(allowance, t * t * (3 - 2 * t));
            }
            return allowance;
        };
        auto flow = [&](float x, float y) {
            V result;
            float weight = 0;
            for (const Anchor &a : anchors) {
                float r2 =
                    ((x - a.p.x) * (x - a.p.x) + (y - a.p.y) * (y - a.p.y)) / (a.radius * a.radius);
                if (r2 >= 1)
                    continue;
                float f = (1 - r2) * (1 - r2) * (1 - r2);
                result = result + a.d * f;
                weight += f;
            }
            if (weight == 0)
                return V{};
            return result * (softProtection(x, y) / std::max(1.f, weight));
        };
        auto sample = [&](float x, float y, uint8_t *dst) {
            x = clamp(x, 0, float(w - 1));
            y = clamp(y, 0, float(h - 1));
            int ix = int(x), iy = int(y);
            float fx = x - ix, fy = y - iy;
            const uint8_t *a = rgba + size_t(iy) * stride + ix * 4;
            const uint8_t *b = rgba + size_t(std::min(iy + 1, h - 1)) * stride + ix * 4;
            int step = ix + 1 < w ? 4 : 0;
            for (int c = 0; c < 4; c++)
                dst[c] = uint8_t(std::lround((a[c] * (1 - fx) + a[step + c] * fx) * (1 - fy) +
                                             (b[c] * (1 - fx) + b[step + c] * fx) * fy));
        };
        for (int y = 0; y < rh; y++)
            for (int x = 0; x < rw; x++) {
                V displacement = flow(float(x + x0), float(y + y0));
                float amount = std::hypot(displacement.x, displacement.y);
                report->max_displacement_px = std::max(report->max_displacement_px, amount);
                sample(x + x0 - displacement.x, y + y0 - displacement.y,
                       scratch.data() + (size_t(y) * rw + x) * 4);
            }
        // Map auxiliary points through the SAME forward field before locating creases.
        std::array<V, 468> mapped = p;
        for (int i = 0; i < 468; i++) {
            V q = p[i];
            for (int iteration = 0; iteration < 5; iteration++)
                q = p[i] + flow(q.x, q.y);
            mapped[i] = q;
        }
        const auto geometryEnd = Clock::now();
        report->geometry_ms =
            std::chrono::duration<double, std::milli>(geometryEnd - geometryStart).count();
        int gw = std::min(768, rw), gh = std::max(1, int(std::lround(float(rh) * gw / rw)));
        float sx = float(rw) / gw, sy = float(rh) / gh;
        std::vector<float> gain(size_t(gw) * gh), blur(gain.size());
        auto luma = [&](float x, float y) {
            int ix = std::clamp(int(x) - x0, 0, rw - 1), iy = std::clamp(int(y) - y0, 0, rh - 1);
            const uint8_t *b = scratch.data() + (size_t(iy) * rw + ix) * 4;
            return .2126f * lut[b[0]] + .7152f * lut[b[1]] + .0722f * lut[b[2]];
        };
        auto base = [&](float x, float y) {
            float sum = 0;
            float d = std::max(1.f, fw / 300);
            for (int j = -1; j <= 1; j++)
                for (int i = -1; i <= 1; i++)
                    sum += luma(x + i * d, y + j * d);
            return sum / 9;
        };
        double confidenceSum = 0;
        int ridges = 0;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) {
                float px = x0 + (x + .5f) * sx, py = y0 + (y + .5f) * sy;
                if (settings->folds <= 0 || protectedPixel(px, py))
                    continue;
                float bestGain = 0;
                for (int side = 0; side < 2; side++) {
                    V a = mapped[side ? 358 : 129], b = mapped[side ? 291 : 61];
                    // Candidate corridor follows the OUTSIDE of the mouth corner;
                    // landmark lines only locate a search region, never a detected crease.
                    float outward = side ? 1.f : -1.f;
                    const V faceRight = pose.rotate({1, 0, 0});
                    a = a + faceRight * (outward * fw * .02f);
                    b = b + faceRight * (outward * fw * .04f);
                    V ab = b - a;
                    float n2 = ab.x * ab.x + ab.y * ab.y;
                    if (n2 < 1)
                        continue;
                    float t = ((px - a.x) * ab.x + (py - a.y) * ab.y) / n2;
                    if (t < .05f || t > .9f)
                        continue;
                    float norm = std::sqrt(n2), nx = -ab.y / norm, ny = ab.x / norm;
                    if ((nx * faceRight.x + ny * faceRight.y) * outward < 0) {
                        nx = -nx;
                        ny = -ny;
                    }
                    float distance = (px - a.x - t * ab.x) * nx + (py - a.y - t * ab.y) * ny -
                                     fw * .025f * std::sin(t * 3.14159265f);
                    float band = fw * .045f;
                    if (std::abs(distance) > band)
                        continue;
                    float centre = base(px, py);
                    if (centre < .035f || centre > .65f)
                        continue;
                    float d = fw * .025f, lo = base(px + nx * d, py + ny * d),
                          hi = base(px - nx * d, py - ny * d);
                    // Use the darker flank as the target, preserving directional lighting.
                    float shoulders = std::min(lo, hi);
                    float contrast = (shoulders - centre) / std::max(.04f, shoulders),
                          balance = std::abs(lo - hi) / std::max(.08f, lo + hi);
                    if (lo <= centre * 1.015f || hi <= centre * 1.015f || contrast < .025f ||
                        contrast > .65f || balance > .45f)
                        continue;
                    float confidence = quality * clamp((.65f - contrast) / .25f) *
                                       clamp((.45f - balance) / .25f) *
                                       clamp((centre - .035f) / .05f);
                    float envelope =
                        std::pow(std::max(0.f, 1 - distance * distance / (band * band)), 2) *
                        std::sin((t - .05f) / .85f * 3.14159265f);
                    float correction =
                        std::min(.12f, std::log(std::max(1.f, shoulders / centre)) * .65f) *
                        settings->folds * confidence * envelope;
                    bestGain = std::max(bestGain, correction);
                    if (correction > .0001f) {
                        confidenceSum += confidence;
                        ridges++;
                    }
                }
                gain[size_t(y) * gw + x] = bestGain;
            }
        // Smooth only the low-frequency luminance correction, never the source texture.
        constexpr float kernel[5] = {1 / 16.f, 4 / 16.f, 6 / 16.f, 4 / 16.f, 1 / 16.f};
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                for (int k = -2; k <= 2; k++)
                    blur[size_t(y) * gw + x] +=
                        gain[size_t(y) * gw + std::clamp(x + k, 0, gw - 1)] * kernel[k + 2];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) {
                float value = 0;
                for (int k = -2; k <= 2; k++)
                    value += blur[size_t(std::clamp(y + k, 0, gh - 1)) * gw + x] * kernel[k + 2];
                gain[size_t(y) * gw + x] = std::max(gain[size_t(y) * gw + x] * .5f, value);
            }
        size_t creaseChanged = 0;
        for (int y = 0; y < rh; y++)
            for (int x = 0; x < rw; x++) {
                if (protectedPixel(float(x + x0), float(y + y0)))
                    continue;
                float gx = clamp((x + .5f) / sx - .5f, 0, float(gw - 1)),
                      gy = clamp((y + .5f) / sy - .5f, 0, float(gh - 1));
                int ix = int(gx), iy = int(gy);
                float fx = gx - ix, fy = gy - iy;
                float a = gain[size_t(iy) * gw + ix] * (1 - fx) +
                          gain[size_t(iy) * gw + std::min(ix + 1, gw - 1)] * fx;
                float b =
                    gain[size_t(std::min(iy + 1, gh - 1)) * gw + ix] * (1 - fx) +
                    gain[size_t(std::min(iy + 1, gh - 1)) * gw + std::min(ix + 1, gw - 1)] * fx;
                float g = a * (1 - fy) + b * fy;
                if (g < .0001f)
                    continue;
                uint8_t *pixel = scratch.data() + (size_t(y) * rw + x) * 4;
                if (std::max({pixel[0], pixel[1], pixel[2]}) > 240)
                    continue;
                float factor = std::exp(g);
                bool changed = false;
                for (int c = 0; c < 3; c++) {
                    uint8_t next = encoded(lut[pixel[c]] * factor);
                    changed |= next != pixel[c];
                    pixel[c] = next;
                }
                creaseChanged += changed;
            }
        size_t changed = 0;
        for (int y = 0; y < rh; y++) {
            uint8_t *dst = rgba + size_t(y + y0) * stride + x0 * 4;
            const uint8_t *src = scratch.data() + size_t(y) * rw * 4;
            for (int x = 0; x < rw; x++)
                changed += std::memcmp(dst + x * 4, src + x * 4, 4) != 0;
        }
        // Commit after every fallible allocation/computation, preserving input on errors.
        if (changed) {
            for (int y = 0; y < rh; y++)
                std::memcpy(rgba + size_t(y + y0) * stride + x0 * 4,
                            scratch.data() + size_t(y) * rw * 4, size_t(rw) * 4);
            for (const Anchor &a : anchors)
                report->applied |= a.flag;
            if (creaseChanged)
                report->applied |= 2;
            report->reason = 0;
        }
        report->changed_pixels = changed;
        report->fold_confidence = ridges ? float(confidenceSum / ridges) : 0;
        report->scratch_bytes = scratch.size() + (gain.size() + blur.size()) * sizeof(float);
        report->folds_ms =
            std::chrono::duration<double, std::milli>(Clock::now() - geometryEnd).count();
        return 0;
    } catch (const std::bad_alloc &) {
        return fail("美颜内存不足，使用正常 LUT");
    } catch (...) {
        return fail("美颜未完成，使用正常 LUT");
    }
}
