#include "g23.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
struct J {
    enum Type { N, B, S, A, O, Z } type = Z;
    double n = 0;
    std::string s;
    std::vector<J> a;
    std::map<std::string, J> o;
    const J &at(const std::string &k) const {
        auto i = o.find(k);
        if (type != O || i == o.end())
            throw std::runtime_error("missing field: " + k);
        return i->second;
    }
    double number(double lo, double hi) const {
        if (type != N || !std::isfinite(n) || n < lo || n > hi)
            throw std::runtime_error("invalid numeric range");
        return n;
    }
};
void members(const J &j, size_t count) {
    if (j.type != J::O || j.o.size() != count)
        throw std::runtime_error("unexpected or missing parameter fields");
}
class Parser {
    const char *p;
    int depth = 0;
    void ws() {
        while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')
            ++p;
    }
    std::string str() {
        if (*p++ != '"')
            throw std::runtime_error("expected string");
        std::string r;
        while (*p && *p != '"') {
            unsigned char c = *p++;
            if (c < 32)
                throw std::runtime_error("control in string");
            if (c == '\\') {
                char e = *p++;
                switch (e) {
                case '"':
                case '\\':
                case '/':
                    r += e;
                    break;
                case 'n':
                    r += '\n';
                    break;
                case 'r':
                    r += '\r';
                    break;
                case 't':
                    r += '\t';
                    break;
                case 'b':
                    r += '\b';
                    break;
                case 'f':
                    r += '\f';
                    break;
                default:
                    throw std::runtime_error("unsupported JSON escape in parameter key");
                }
            } else
                r += char(c);
        }
        if (*p++ != '"')
            throw std::runtime_error("unterminated string");
        return r;
    }
    J val() {
        ws();
        if (++depth > 16)
            throw std::runtime_error("JSON depth");
        J j;
        if (*p == '{') {
            j.type = J::O;
            ++p;
            ws();
            if (*p != '}')
                for (;;) {
                    ws();
                    auto k = str();
                    ws();
                    if (*p++ != ':')
                        throw std::runtime_error("expected colon");
                    if (j.o.count(k))
                        throw std::runtime_error("duplicate key");
                    j.o[k] = val();
                    ws();
                    if (*p != ',')
                        break;
                    ++p;
                }
            if (*p++ != '}')
                throw std::runtime_error("expected object end");
        } else if (*p == '[') {
            j.type = J::A;
            ++p;
            ws();
            if (*p != ']')
                for (;;) {
                    if (j.a.size() > 256)
                        throw std::runtime_error("array too long");
                    j.a.push_back(val());
                    ws();
                    if (*p != ',')
                        break;
                    ++p;
                }
            if (*p++ != ']')
                throw std::runtime_error("expected array end");
        } else if (*p == '"') {
            j.type = J::S;
            j.s = str();
        } else if (!strncmp(p, "null", 4)) {
            p += 4;
        } else if (!strncmp(p, "true", 4)) {
            p += 4;
            j.type = J::B;
            j.n = 1;
        } else if (!strncmp(p, "false", 5)) {
            p += 5;
            j.type = J::B;
        } else {
            const char *start = p;
            if (*p == '-')
                ++p;
            if (*p == '0')
                ++p;
            else {
                if (*p < '1' || *p > '9')
                    throw std::runtime_error("invalid JSON number");
                while (*p >= '0' && *p <= '9')
                    ++p;
            }
            if (*p == '.') {
                ++p;
                if (*p < '0' || *p > '9')
                    throw std::runtime_error("invalid fraction");
                while (*p >= '0' && *p <= '9')
                    ++p;
            }
            if (*p == 'e' || *p == 'E') {
                ++p;
                if (*p == '+' || *p == '-')
                    ++p;
                if (*p < '0' || *p > '9')
                    throw std::runtime_error("invalid exponent");
                while (*p >= '0' && *p <= '9')
                    ++p;
            }
            j.type = J::N;
            j.n = std::strtod(start, nullptr);
        }
        --depth;
        return j;
    }

  public:
    explicit Parser(const char *x) : p(x) {}
    J parse() {
        J j = val();
        ws();
        if (*p)
            throw std::runtime_error("trailing JSON");
        return j;
    }
};
float clamp(float x, float lo = 0, float hi = 1) { return std::clamp(x, lo, hi); }
float smooth(float a, float b, float x) {
    float t = clamp((x - a) / (b - a));
    return t * t * (3 - 2 * t);
}
struct RGB {
    float r = 0, g = 0, b = 0;
    float &operator[](int i) { return i == 0 ? r : i == 1 ? g : b; }
    float operator[](int i) const { return i == 0 ? r : i == 1 ? g : b; }
};
float lum(RGB c) { return .2126f * c.r + .7152f * c.g + .0722f * c.b; }
float linear(float v) { return v <= .04045f ? v / 12.92f : std::pow((v + .055f) / 1.055f, 2.4f); }
float decode(uint8_t v) {
    static const auto table = []() {
        std::array<float, 256> a{};
        for (int i = 0; i < 256; ++i)
            a[i] = linear(i / 255.f);
        return a;
    }();
    return table[v];
}
float srgb(float v) { return v <= .0031308f ? 12.92f * v : 1.055f * std::pow(v, 1 / 2.4f) - .055f; }
struct Curve {
    std::vector<float> x, y, m;
    explicit Curve(const J &j) {
        if (j.type != J::A || j.a.size() < 2 || j.a.size() > 32)
            throw std::runtime_error("curve needs 2..32 points");
        for (const auto &v : j.a) {
            if (v.type != J::A || v.a.size() != 2)
                throw std::runtime_error("curve point");
            x.push_back(v.a[0].number(0, 1));
            y.push_back(v.a[1].number(0, 1));
            if (x.size() > 1 && x.back() - x[x.size() - 2] < .0001)
                throw std::runtime_error("curve x must increase");
        }
        if (x.front() != 0 || x.back() != 1)
            throw std::runtime_error("curve endpoints x must be 0 and 1");
        size_t n = x.size();
        std::vector<float> d(n - 1), h(n - 1);
        m.resize(n);
        for (size_t i = 0; i + 1 < n; ++i) {
            h[i] = x[i + 1] - x[i];
            d[i] = (y[i + 1] - y[i]) / h[i];
        }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (size_t i = 1; i + 1 < n; ++i) {
            if (d[i - 1] * d[i] <= 0)
                m[i] = 0;
            else {
                float w1 = 2 * h[i] + h[i - 1], w2 = h[i] + 2 * h[i - 1];
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
            }
        }
    }
    float eval(float v) const {
        v = clamp(v);
        size_t i =
            std::min(size_t(std::upper_bound(x.begin(), x.end(), v) - x.begin()), x.size() - 1) - 1;
        float h = x[i + 1] - x[i], t = (v - x[i]) / h;
        return clamp((2 * t * t * t - 3 * t * t + 1) * y[i] +
                     (t * t * t - 2 * t * t + t) * h * m[i] +
                     (-2 * t * t * t + 3 * t * t) * y[i + 1] + (t * t * t - t * t) * h * m[i + 1]);
    }
};
RGB fromHsl(float h, float s, float l) {
    h -= std::floor(h);
    float c = (1 - std::abs(2 * l - 1)) * s;
    float hp = h * 6, x = c * (1 - std::abs(std::fmod(hp, 2.f) - 1));
    RGB r;
    if (hp < 1)
        r = {c, x, 0};
    else if (hp < 2)
        r = {x, c, 0};
    else if (hp < 3)
        r = {0, c, x};
    else if (hp < 4)
        r = {0, x, c};
    else if (hp < 5)
        r = {x, 0, c};
    else
        r = {c, 0, x};
    float m = l - c / 2;
    return {r.r + m, r.g + m, r.b + m};
}
struct Params {
    float b[10], hsl[8][3], grade[3][3], balance, blend, t[7];
    uint32_t seed;
    std::vector<Curve> curves;
    bool tonal = false, curved = false, hslOn = false, graded = false;
    float exposure = 1, wb[3]{1, 1, 1}, contrastFactor = 1;
    RGB gradingColor[3];

    explicit Params(const J &root) {
        members(root, 4);
        if (root.at("schema_version").s != "1.0" || root.at("render_pipeline_version").s != "1.0.0")
            throw std::runtime_error("unsupported schema or pipeline");
        const char *order[] = {"orientation_and_icc_to_srgb",
                               "geometry",
                               "srgb_to_linear",
                               "white_balance_exposure",
                               "basic_tone",
                               "linear_to_srgb",
                               "master_then_channel_curves",
                               "hsl_vibrance_saturation",
                               "color_grading",
                               "fade",
                               "sharpness_clarity_local_contrast",
                               "grain_vignette",
                               "srgb_rgba8_encode"};
        const J &stages = root.at("processing_order");
        if (stages.type != J::A || stages.a.size() != sizeof(order) / sizeof(order[0]))
            throw std::runtime_error("invalid processing order");
        for (size_t i = 0; i < stages.a.size(); ++i)
            if (stages.a[i].type != J::S || stages.a[i].s != order[i])
                throw std::runtime_error("invalid processing order");
        const J &p = root.at("parameters");
        members(p, 15);
        members(p.at("hsl"), 8);
        members(p.at("tone_curves"), 4);
        const char *names[] = {"exposure_ev", "contrast",  "highlights",        "shadows",
                               "whites",      "blacks",    "temperature_shift", "tint",
                               "vibrance",    "saturation"};
        for (int i = 0; i < 10; ++i)
            b[i] = p.at(names[i]).number(i == 0 ? -5 : -100, i == 0 ? 5 : 100);
        const char *colors[] = {"red",  "orange", "yellow", "green",
                                "cyan", "blue",   "purple", "magenta"};
        for (int i = 0; i < 8; ++i) {
            const auto &a = p.at("hsl").at(colors[i]);
            members(a, 3);
            hsl[i][0] = a.at("hue").number(-100, 100);
            hsl[i][1] = a.at("saturation").number(-100, 100);
            hsl[i][2] = a.at("luminance").number(-100, 100);
        }
        for (auto n : {"rgb", "red", "green", "blue"})
            curves.emplace_back(p.at("tone_curves").at(n));
        const auto &g = p.at("color_grading");
        members(g, 5);
        const char *zones[] = {"shadows", "midtones", "highlights"};
        for (int i = 0; i < 3; ++i) {
            const auto &a = g.at(zones[i]);
            members(a, 3);
            grade[i][0] = a.at("hue").number(0, 360);
            grade[i][1] = a.at("saturation").number(0, 100);
            grade[i][2] = a.at("luminance").number(-100, 100);
        }
        balance = g.at("balance").number(-100, 100);
        blend = g.at("blending").number(0, 100);
        const auto &tx = p.at("texture");
        members(tx, 8);
        const char *tn[] = {"sharpness", "clarity", "local_contrast", "grain_amount", "grain_size",
                            "vignette",  "fade"};
        for (int i = 0; i < 7; ++i)
            t[i] = tx.at(tn[i]).number(i == 1 || i == 2 || i == 5 ? -100 : 0, 100);
        double sd = tx.at("grain_seed").number(0, 4294967295.);
        if (sd != std::floor(sd))
            throw std::runtime_error("grain_seed must be integer");
        seed = uint32_t(sd);
        const auto &geo = p.at("geometry");
        members(geo, 2);
        if (geo.at("rotation_degrees").number(0, 270) != 0 || geo.at("crop").type != J::Z)
            throw std::runtime_error("apply geometry in adapter; pass identity to renderer");
        for (int i = 0; i < 8; ++i)
            tonal = tonal || b[i] != 0;
        exposure = std::exp2(b[0]);
        contrastFactor = std::exp2(b[1] / 100);
        wb[0] = std::exp2(.5f * b[6] / 100 - .15f * b[7] / 100);
        wb[1] = std::exp2(.3f * b[7] / 100);
        wb[2] = std::exp2(-.5f * b[6] / 100 - .15f * b[7] / 100);
        for (const auto &curve : curves)
            for (size_t i = 0; i < curve.x.size(); ++i)
                curved = curved || curve.x[i] != curve.y[i];
        hslOn = b[8] != 0 || b[9] != 0;
        for (auto &channel : hsl)
            for (float v : channel)
                hslOn = hslOn || v != 0;
        for (int z = 0; z < 3; ++z) {
            graded = graded || grade[z][1] != 0 || grade[z][2] != 0;
            RGB color = fromHsl(grade[z][0] / 360, 1, .5);
            float yl = lum(color);
            gradingColor[z] = {color.r - yl, color.g - yl, color.b - yl};
        }
    }
    RGB pixel(const uint8_t *in) const {
        RGB c = {in[0] / 255.f, in[1] / 255.f, in[2] / 255.f};
        if (tonal) {
            for (int i = 0; i < 3; ++i)
                c[i] = decode(in[i]) * exposure * wb[i];
            float l = lum(c), shadow = 1 - smooth(0, .65, l), hi = smooth(.35, 1, l);
            float ev = (b[2] * hi + b[3] * shadow) / 100 * .7f;
            float gain = std::exp2(ev);
            for (int i = 0; i < 3; ++i) {
                float v = c[i] * gain;
                v += b[4] / 100 * .25f * smooth(.5, 1, v) +
                     b[5] / 100 * .15f * (1 - smooth(0, .35, v));
                v = (v - .18f) * contrastFactor + .18f;
                c[i] = srgb(clamp(v));
            }
        }
        if (curved)
            for (int i = 0; i < 3; ++i)
                c[i] = curves[i + 1].eval(curves[0].eval(c[i]));
        if (hslOn) {
            float mx = std::max({c.r, c.g, c.b}), mn = std::min({c.r, c.g, c.b}), delta = mx - mn,
                  light = (mx + mn) / 2,
                  sat = delta == 0 ? 0 : delta / (1 - std::abs(2 * light - 1));
            float hue = 0;
            if (delta > 1e-8) {
                if (mx == c.r)
                    hue = std::fmod((c.g - c.b) / delta, 6.f);
                else if (mx == c.g)
                    hue = (c.b - c.r) / delta + 2;
                else
                    hue = (c.r - c.g) / delta + 4;
                hue /= 6;
                if (hue < 0)
                    hue += 1;
            }
            const float centers[] = {0, 30, 60, 120, 180, 240, 270, 300};
            float wsum = 0, dh = 0, ds = 0, dl = 0;
            for (int i = 0; i < 8; ++i) {
                float d = std::abs(hue * 360 - centers[i]);
                d = std::min(d, 360 - d);
                float w = d < 60 ? .5f + .5f * std::cos(d * 3.14159265f / 60) : 0;
                wsum += w;
                dh += w * hsl[i][0];
                ds += w * hsl[i][1];
                dl += w * hsl[i][2];
            }
            if (wsum > 0) {
                hue += dh / wsum / 100 * (30.f / 360);
                sat = clamp(sat * (1 + ds / wsum / 100));
                light = clamp(light + dl / wsum / 100 * .25f * (delta > 0 ? 1 : 0));
            }
            sat = clamp(sat * (1 + b[9] / 100) * (1 + b[8] / 100 * (1 - sat)));
            c = fromHsl(hue, sat, light);
        }
        if (graded) {
            float l = lum(c);
            float pivot = .5f + balance / 100 * .2f, width = .05f + blend / 100 * .4f;
            float shadows = 1 - smooth(pivot - width, pivot + width, l),
                  high = smooth(pivot - width, pivot + width, l);
            float mid = 4 * shadows * high;
            float weights[] = {shadows * (1 - mid), mid, high * (1 - mid)};
            for (int z = 0; z < 3; ++z) {
                float strength = weights[z] * grade[z][1] / 100 * .25f;
                for (int i = 0; i < 3; ++i)
                    c[i] += gradingColor[z][i] * strength + weights[z] * grade[z][2] / 100 * .15f;
            }
        }
        for (int i = 0; i < 3; ++i) {
            c[i] = clamp(c[i]) * (1 - t[6] / 100 * .3f) + t[6] / 100 * .12f;
        }
        return c;
    }
};
uint32_t hash(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}
float noise(int x, int y, uint32_t seed) {
    return (hash(uint32_t(x) * 73856093U ^ uint32_t(y) * 19349663U ^ seed) / 4294967295.f) * 2 - 1;
}
} // namespace
extern "C" const char *g23_pipeline_version() { return "1.0.0"; }
extern "C" int g23_render(const uint8_t *input, uint8_t *output, int w, int h, const char *json,
                          char *err, size_t cap) {
    auto fail = [&](const char *s, int code) {
        if (err && cap)
            std::snprintf(err, cap, "%s", s);
        return code;
    };
    try {
        if (!input || !output || input == output || !json || w < 1 || h < 1 ||
            int64_t(w) * h > 100000000 || std::strlen(json) > 65536)
            return fail("invalid buffers, size or JSON length", 1);
        Params p(Parser(json).parse());
        auto finish = [&](int x, int y) {
            float v = 1, n = 0;
            if (p.t[5] != 0) {
                float nx = (x + .5f) / w * 2 - 1, ny = (y + .5f) / h * 2 - 1;
                v = 1 + p.t[5] / 100 * .75f * smooth(.1, 1, (nx * nx + ny * ny) / 2);
            }
            if (p.t[3] != 0) {
                float gs = 1 + p.t[4] / 100 * 7, fx = x / gs, fy = y / gs;
                int ix = int(fx), iy = int(fy);
                float ax = smooth(0, 1, fx - ix), ay = smooth(0, 1, fy - iy);
                n = (1 - ay) * ((1 - ax) * noise(ix, iy, p.seed) + ax * noise(ix + 1, iy, p.seed)) +
                    ay *
                        ((1 - ax) * noise(ix, iy + 1, p.seed) + ax * noise(ix + 1, iy + 1, p.seed));
            }
            return std::pair<float, float>{v, n};
        };
        if (p.t[0] == 0 && p.t[1] == 0 && p.t[2] == 0) {
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    size_t index = 4 * (size_t(y) * w + x);
                    RGB c = p.pixel(input + index);
                    auto [v, n] = finish(x, y);
                    for (int k = 0; k < 3; ++k)
                        output[index + k] =
                            uint8_t(std::lround(clamp(c[k] * v + n * p.t[3] / 100 * .08f) * 255));
                    output[index + 3] = input[index + 3];
                }
            if (err && cap)
                *err = 0;
            return 0;
        }
        // Tiled integral images bound working memory; image coordinates determine noise.
        int rs = 1, rc = std::max(2, int(std::min(w, h) / 128.f)),
            rl = std::max(4, int(std::min(w, h) / 32.f));
        rl = std::min(rl, 128);
        rc = std::min(rc, 32);
        int halo = p.t[2] != 0 ? rl : p.t[1] != 0 ? rc : rs;
        for (int ty = 0; ty < h; ty += 128)
            for (int tx = 0; tx < w; tx += 256) {
                int x0 = std::max(0, tx - halo), y0 = std::max(0, ty - halo),
                    x1 = std::min(w, tx + 256 + halo), y1 = std::min(h, ty + 128 + halo),
                    tw = x1 - x0, th = y1 - y0, stride = tw + 1;
                std::vector<RGB> pixels(size_t(tw) * th);
                std::vector<std::array<double, 3>> sums(size_t(tw + 1) * (th + 1));
                for (int y = 0; y < th; ++y)
                    for (int x = 0; x < tw; ++x) {
                        RGB c = p.pixel(input + 4 * (size_t(y + y0) * w + x + x0));
                        pixels[size_t(y) * tw + x] = c;
                        for (int k = 0; k < 3; ++k)
                            sums[size_t(y + 1) * stride + x + 1][k] =
                                c[k] + sums[size_t(y) * stride + x + 1][k] +
                                sums[size_t(y + 1) * stride + x][k] -
                                sums[size_t(y) * stride + x][k];
                    }
                auto blur = [&](int x, int y, int r, int k) {
                    int a = std::max(x0, x - r) - x0, b = std::min(x1, x + r + 1) - x0,
                        c = std::max(y0, y - r) - y0, d = std::min(y1, y + r + 1) - y0;
                    return float(
                        (sums[size_t(d) * stride + b][k] - sums[size_t(c) * stride + b][k] -
                         sums[size_t(d) * stride + a][k] + sums[size_t(c) * stride + a][k]) /
                        ((b - a) * (d - c)));
                };
                for (int y = ty; y < std::min(h, ty + 128); ++y)
                    for (int x = tx; x < std::min(w, tx + 256); ++x) {
                        RGB c = pixels[size_t(y - y0) * tw + x - x0];
                        auto [v, n] = finish(x, y);
                        for (int k = 0; k < 3; ++k) {
                            float sharp = p.t[0] != 0
                                              ? std::clamp(c[k] - blur(x, y, rs, k), -.08f, .08f) *
                                                    p.t[0] / 100
                                              : 0;
                            float clarity =
                                p.t[1] != 0 ? (c[k] - blur(x, y, rc, k)) * p.t[1] / 100 * .5f : 0;
                            float local =
                                p.t[2] != 0 ? (c[k] - blur(x, y, rl, k)) * p.t[2] / 100 * .5f : 0;
                            float value =
                                (c[k] + sharp + clarity + local) * v + n * p.t[3] / 100 * .08f;
                            output[4 * (size_t(y) * w + x) + k] =
                                uint8_t(std::lround(clamp(value) * 255));
                        }
                        output[4 * (size_t(y) * w + x) + 3] = input[4 * (size_t(y) * w + x) + 3];
                    }
            }
        if (err && cap) {
            *err = 0;
        }
        return 0;
    } catch (const std::bad_alloc &) {
        return fail("not enough memory", 2);
    } catch (const std::exception &e) {
        return fail(e.what(), 1);
    }
}
