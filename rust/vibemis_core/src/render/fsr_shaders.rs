//! AMD FidelityFX Super Resolution 1.0 (EASU + RCAS) GLSL shaders and sharpness curves.
//! Ported 1:1 from `FsrShaders.kt`, preserving `samplerExternalOES` texel stepping for Adreno/Mali.

pub const LUMA_709: [f32; 3] = [0.2126, 0.7152, 0.0722];
pub const LUMA_2020: [f32; 3] = [0.2627, 0.6780, 0.0593];

#[inline]
pub fn clamp01(v: f32) -> f32 {
    v.clamp(0.0, 1.0)
}

#[inline]
pub fn map_ui_sharp_to_internal(ui: f32, near_native: bool) -> f32 {
    let s = clamp01(ui);
    if s <= 0.02 {
        return 0.0;
    }
    if near_native {
        0.55 * s
    } else {
        s
    }
}

#[inline]
pub fn map_ui_sharp_to_internal_easu_rcas_linear(ui: f32, preset_in: i32) -> f32 {
    let mut s = clamp01(ui);
    if s <= 0.02 {
        return 0.0;
    }
    let preset = preset_in.clamp(0, 2);
    let gain = match preset {
        0 => 1.35,
        1 => 1.10,
        _ => 1.00,
    };
    s *= gain;
    s.min(1.0)
}

#[inline]
pub fn map_ui_sharp_to_internal_rcas(ui: f32, preset_in: i32, near_native: bool) -> f32 {
    let mut s = clamp01(ui);
    if ui <= 0.05 {
        return 0.0;
    }
    s = (s - 0.05) / 0.95;
    s = 1.0 - (-3.0 * s).exp();
    if s <= 0.05 {
        return 0.0;
    }
    s = (s - 0.05) / 0.95;
    s = 1.0 - (-3.0 * s).exp();

    let preset = preset_in.clamp(0, 2);
    let cap = match preset {
        0 => if near_native { 0.18 } else { 0.25 },
        1 => if near_native { 0.28 } else { 0.40 },
        _ => if near_native { 0.36 } else { 0.55 },
    };
    cap * s
}

pub fn fs_oes_to_2d(oes_fs: &str) -> String {
    oes_fs
        .replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "")
        .replace("precision highp samplerExternalOES;\n", "")
        .replace("samplerExternalOES", "sampler2D")
}

pub const VS: &str = "\
#version 300 es
precision highp float;
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
out vec2 vUv;
void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0);}";

pub const VS_BLIT_TEXMAT: &str = "\
#version 300 es
precision highp float;
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main(){ vUv=(uTexMatrix*vec4(aUv,0.0,1.0)).xy; gl_Position=vec4(aPos,0.0,1.0);}";

pub const VS_RCAS_OES: &str = "\
#version 300 es
precision highp float;
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUv;
out vec2 vUv;
out vec2 vUv0;
out vec2 vStepX;
out vec2 vStepY;
uniform mat4 uTexMatrix;
uniform vec2 uInvDstSize;
void main(){
  vUv = aUv;
  vec2 texel = uInvDstSize;
  vUv0   = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
  vStepX = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;
  vStepY = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;
  gl_Position = vec4(aPos, 0.0, 1.0);
}";

pub const FS_OES_BLIT: &str = "\
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp samplerExternalOES;
in vec2 vUv;
layout(location=0) out vec4 fragColor;
uniform samplerExternalOES uTex;
void main(){ fragColor = texture(uTex, vUv); }";

pub const FS_OES_TO_2D: &str = "\
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 fragColor;
uniform samplerExternalOES uTex;
void main(){ fragColor = texture(uTex, vUv); }";

pub const FS_EASU_QUALITY: &str = "\
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
precision highp samplerExternalOES;

in vec2 vUv;
layout(location=0) out vec4 fragColor;

uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uInvSrcSize;

float luma(vec3 c){ return c.g + 0.5 * (c.r + c.b); }

float APrxLoRcpF1(float a){ return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a)); }
float APrxLoRsqF1(float a){ return uintBitsToFloat(uint(0x5f347d74) - (floatBitsToUint(a) >> uint(1))); }
float AMin3F1(float x, float y, float z){ return min(x, min(y, z)); }
float AMax3F1(float x, float y, float z){ return max(x, max(y, z)); }

void FsrEasuTap(
    inout vec3 aC,
    inout float aW,
    vec2 off,
    vec2 dir,
    vec2 len,
    float lob,
    float clp,
    vec3 c)
{
    vec2 v;
    v.x = (off.x * ( dir.x)) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    v *= len;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    float wB = (2.0 / 5.0) * d2 + -1.0;
    float wA = lob * d2 + -1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB + (-(25.0 / 16.0 - 1.0));
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void FsrEasuSet(
    inout vec2 dir,
    inout float len,
    vec2 pp,
    bool biS, bool biT, bool biU, bool biV,
    float lA, float lB, float lC, float lD, float lE)
{
    float w = 0.0;
    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (biT) w = (pp.x) * (1.0 - pp.y);
    if (biU) w = (1.0 - pp.x) * (pp.y);
    if (biV) w = (pp.x) * (pp.y);

    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = APrxLoRcpF1(lenX);
    float dirX = lD - lB;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = APrxLoRcpF1(lenY);
    float dirY = lE - lA;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;

    dir += vec2(dirX, dirY) * w;
    len += w * (lenX + lenY);
}

vec2 sampleUv(vec2 baseUv, vec2 stepX, vec2 stepY, float ox, float oy){
    return baseUv + stepX * ox + stepY * oy;
}

void main(){
    vec2 srcSize = 1.0 / max(uInvSrcSize, vec2(1e-6));
    vec2 pp = vUv * srcSize - vec2(0.5);
    vec2 fp = floor(pp);
    pp -= fp;

    vec2 baseSrcUv = (fp + vec2(0.5)) * uInvSrcSize;
    vec2 baseUv = (uTexMatrix * vec4(baseSrcUv, 0.0, 1.0)).xy;
    vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
    vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

    vec3 bC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0, -1.0)).rgb;
    vec3 cC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0, -1.0)).rgb;
    vec3 eC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  0.0)).rgb;
    vec3 fC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  0.0)).rgb;
    vec3 gC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  0.0)).rgb;
    vec3 hC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  0.0)).rgb;
    vec3 iC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  1.0)).rgb;
    vec3 jC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  1.0)).rgb;
    vec3 kC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  1.0)).rgb;
    vec3 lC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  1.0)).rgb;
    vec3 nC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  2.0)).rgb;
    vec3 oC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  2.0)).rgb;

    float bL = luma(bC);
    float cL = luma(cC);
    float eL = luma(eC);
    float fL = luma(fC);
    float gL = luma(gC);
    float hL = luma(hC);
    float iL = luma(iC);
    float jL = luma(jC);
    float kL = luma(kC);
    float lL = luma(lC);
    float nL = luma(nC);
    float oL = luma(oC);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    FsrEasuSet(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);
    FsrEasuSet(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);
    FsrEasuSet(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);
    FsrEasuSet(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    const float DIR_THRESHOLD = 32768.0;
    bool zro = dirR < (1.0 / DIR_THRESHOLD);
    dirR = APrxLoRsqF1(dirR);
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);

    len = len * 0.5;
    len *= len;

    float stretch = (dir.x * dir.x + dir.y * dir.y) * APrxLoRcpF1(max(abs(dir.x), abs(dir.y)));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);

    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = APrxLoRcpF1(lob);

    vec3 aC = vec3(0.0);
    float aW = 0.0;

    FsrEasuTap(aC, aW, vec2( 0.0,-1.0) - pp, dir, len2, lob, clp, bC);
    FsrEasuTap(aC, aW, vec2( 1.0,-1.0) - pp, dir, len2, lob, clp, cC);
    FsrEasuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iC);
    FsrEasuTap(aC, aW, vec2( 0.0, 1.0) - pp, dir, len2, lob, clp, jC);
    FsrEasuTap(aC, aW, vec2( 0.0, 0.0) - pp, dir, len2, lob, clp, fC);
    FsrEasuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eC);
    FsrEasuTap(aC, aW, vec2( 1.0, 1.0) - pp, dir, len2, lob, clp, kC);
    FsrEasuTap(aC, aW, vec2( 2.0, 1.0) - pp, dir, len2, lob, clp, lC);
    FsrEasuTap(aC, aW, vec2( 2.0, 0.0) - pp, dir, len2, lob, clp, hC);
    FsrEasuTap(aC, aW, vec2( 1.0, 0.0) - pp, dir, len2, lob, clp, gC);
    FsrEasuTap(aC, aW, vec2( 1.0, 2.0) - pp, dir, len2, lob, clp, oC);
    FsrEasuTap(aC, aW, vec2( 0.0, 2.0) - pp, dir, len2, lob, clp, nC);

    vec3 pix = aC / max(aW, 1e-6);
    vec3 mn = min(min(fC, gC), min(jC, kC));
    vec3 mx = max(max(fC, gC), max(jC, kC));
    pix = clamp(pix, mn, mx);
    fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
}";

pub const FS_EASU_BALANCED: &str = "\
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision highp samplerExternalOES;

in highp vec2 vUv;
layout(location=0) out vec4 fragColor;
uniform samplerExternalOES uTex;
uniform highp vec2 uInvSrcSize;
uniform highp mat4 uTexMatrix;

mediump float luma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }

void main(){
  highp vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
  highp vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
  highp vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

  mediump vec3 c = texture(uTex, uv).rgb;
  mediump vec3 l = texture(uTex, uv - stepX).rgb;
  mediump vec3 r = texture(uTex, uv + stepX).rgb;
  mediump vec3 d = texture(uTex, uv - stepY).rgb;
  mediump vec3 u = texture(uTex, uv + stepY).rgb;

  mediump float gx = luma(r) - luma(l);
  mediump float gy = luma(u) - luma(d);
  mediump float ax = abs(gx);
  mediump float ay = abs(gy);
  mediump float edge = clamp((ax + ay) * 1.40, 0.0, 1.0);

  mediump vec2 ed = normalize(vec2(gy, -gx) + vec2(1e-6));
  highp vec2 duv = ed.x * stepX + ed.y * stepY;

  mediump vec3 s1 = texture(uTex, uv + duv * 0.5).rgb;
  mediump vec3 s2 = texture(uTex, uv - duv * 0.5).rgb;
  mediump vec3 along = 0.5 * (s1 + s2);

  mediump float dirConfidence = abs(ax - ay) / (ax + ay + 1e-4);
  mediump float blend = edge * (0.66 + 0.10 * dirConfidence);
  mediump vec3 pix = mix(c, along, blend);

  mediump vec3 mn = min(c, min(min(l, r), min(u, d)));
  mediump vec3 mx = max(c, max(max(l, r), max(u, d)));
  pix = clamp(pix, mn, mx);
  fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
}";

pub const FS_EASU_PERF: &str = "\
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp samplerExternalOES;
in vec2 vUv;
layout(location=0) out vec4 fragColor;
uniform samplerExternalOES uTex;
uniform vec2 uInvSrcSize;
uniform mat4 uTexMatrix;

mediump float luma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }

void main(){
  highp vec2 uv     = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
  highp vec2 stepX  = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
  highp vec2 stepY  = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

  mediump vec3 c = texture(uTex, uv).rgb;
  mediump vec3 l = texture(uTex, uv - stepX).rgb;
  mediump vec3 r = texture(uTex, uv + stepX).rgb;
  mediump vec3 t = texture(uTex, uv + stepY).rgb;
  mediump vec3 b = texture(uTex, uv - stepY).rgb;

  mediump float lc = luma(c);
  mediump float ll = luma(l);
  mediump float lr = luma(r);
  mediump float lt = luma(t);
  mediump float lb = luma(b);

  mediump float gx = abs(lr - ll);
  mediump float gy = abs(lt - lb);
  mediump float invG = 1.0 / (gx + gy + 1e-4);
  mediump float verticalEdge = gx * invG;
  mediump float anis = abs(gx - gy) * invG;

  mediump float lowIso = 0.25 * (ll + lr + lt + lb);
  mediump float lowH   = 0.5 * (ll + lr);
  mediump float lowV   = 0.5 * (lt + lb);
  mediump float lowDir = mix(lowH, lowV, verticalEdge);
  mediump float low = mix(lowIso, lowDir, 0.78 * anis);

  mediump float detail = lc - low;
  mediump float ad = max(abs(detail) - (1.0 / 512.0), 0.0);
  detail = (detail < 0.0) ? -ad : ad;

  mediump float gain = 0.10 + 0.055 * anis;
  mediump float l2 = lc + detail * gain;

  mediump float mn = min(lc, min(min(ll, lr), min(lt, lb)));
  mediump float mx = max(lc, max(max(ll, lr), max(lt, lb)));
  mediump float pad = 0.008 + 0.018 * anis;
  l2 = clamp(l2, mn - pad, mx + pad);

  mediump float delta = l2 - lc;
  mediump float scale = (lc > 1e-3) ? (l2 / lc) : 1.0;
  mediump vec3 scaled = c * scale;
  mediump vec3 additive = c + vec3(delta);
  mediump float useScale = step(1e-3, lc);
  mediump vec3 outRgb = mix(additive, scaled, useScale);
  fragColor = vec4(clamp(outRgb, 0.0, 1.0), 1.0);
}";

pub const FS_RCAS: &str = "\
#version 300 es
#ifdef USE_OES
#extension GL_OES_EGL_image_external_essl3 : require
#endif
precision highp float;

in vec2 vUv;
#if defined(USE_OES) && defined(RCAS_OES_VS)
in vec2 vUv0;
in vec2 vStepX;
in vec2 vStepY;
#endif

layout(location=0) out vec4 fragColor;

#ifdef USE_OES
uniform samplerExternalOES uTexOES;
uniform mat4 uTexMatrix;
#else
uniform sampler2D uUpscaled;
#endif

uniform vec2  uInvDstSize;
uniform float uSharp;
uniform vec3  uLumaCoeffs;

float luma(vec3 c){ return dot(c, uLumaCoeffs); }

void main(){
#ifdef USE_OES
  #ifdef RCAS_OES_VS
    vec3 c  = texture(uTexOES, vUv0).rgb;
    vec3 rx = texture(uTexOES, vUv0 + vStepX).rgb;
    vec3 lx = texture(uTexOES, vUv0 - vStepX).rgb;
    vec3 ty = texture(uTexOES, vUv0 + vStepY).rgb;
    vec3 by = texture(uTexOES, vUv0 - vStepY).rgb;
  #else
    vec2 texel = uInvDstSize;
    vec2 uv0    = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
    vec2 stepX  = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;
    vec2 stepY  = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;
    vec3 c  = texture(uTexOES, uv0).rgb;
    vec3 rx = texture(uTexOES, uv0 + stepX).rgb;
    vec3 lx = texture(uTexOES, uv0 - stepX).rgb;
    vec3 ty = texture(uTexOES, uv0 + stepY).rgb;
    vec3 by = texture(uTexOES, uv0 - stepY).rgb;
  #endif
#else
  vec2 texel = uInvDstSize;
  vec2 uv0 = vUv;
  vec3 c  = texture(uUpscaled, uv0).rgb;
  vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;
  vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;
  vec3 ty = texture(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;
  vec3 by = texture(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;
#endif

  float sharp = clamp(uSharp, 0.0, 1.0);
  if (sharp <= 0.0001) {
    fragColor = vec4(c, 1.0);
    return;
  }

#ifdef RCAS_LUMA_ONLY
  float lc  = luma(c);
  float lrx = luma(rx);
  float llx = luma(lx);
  float lty = luma(ty);
  float lby = luma(by);

  float blur4  = 0.25 * (lrx + llx + lty + lby);
  float detail = lc - blur4;
  float edgeX = lrx - llx;
  float edgeY = lty - lby;
  float edgeW = 1.0 / (1.0 + 8.0 * (edgeX * edgeX + edgeY * edgeY));
  float outL = lc + detail * (1.35 * sharp * edgeW);

  float lo = min(lc, min(min(llx, lrx), min(lty, lby)));
  float hi = max(lc, max(max(llx, lrx), max(lty, lby)));
  float pad = 0.01 + 0.04 * sharp;
  outL = clamp(outL, lo - pad, hi + pad);

  float scale = (lc > 1e-5) ? (outL / lc) : 1.0;
  fragColor = vec4(clamp(c * scale, 0.0, 1.0), 1.0);
#else
  vec3 mn4 = min(min(lx, rx), min(ty, by));
  vec3 mx4 = max(max(lx, rx), max(ty, by));

  vec3 hitMin = min(mn4, c) / max(4.0 * mx4, vec3(1e-6));
  vec3 denMax = min(4.0 * mn4 - vec3(4.0), vec3(-1e-6));
  vec3 hitMax = (vec3(1.0) - max(mx4, c)) / denMax;
  vec3 lobeRGB = max(-hitMin, hitMax);

  const float RCAS_LIMIT = 0.1875;
  float lobe = max(-RCAS_LIMIT,
                   min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0));
  lobe *= sharp;

  float rcpL = 1.0 / max(4.0 * lobe + 1.0, 1e-5);
  vec3 outc = (c + lobe * (lx + rx + ty + by)) * rcpL;
  fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
#endif
}";

pub const FS_RCAS_FAST: &str = "\
#version 300 es
precision highp float;
in vec2 vUv;
layout(location=0) out vec4 fragColor;
uniform sampler2D uUpscaled;
uniform vec2  uInvDstSize;
uniform float uSharp;
uniform vec3  uLumaCoeffs;
mediump float fastLuma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }
void main(){
  highp vec2 texel = uInvDstSize;
  highp vec2 uv0 = vUv;
  mediump vec3 c = texture(uUpscaled, uv0).rgb;
  mediump float sharp = clamp(uSharp, 0.0, 1.0);
  if (sharp <= 0.0001) { fragColor = vec4(c, 1.0); return; }

  mediump float lc = fastLuma(c);
  mediump float dx = dFdx(lc);
  mediump float dy = dFdy(lc);
  mediump float useY = step(abs(dy), abs(dx));
  highp vec2 axis = mix(vec2(texel.x, 0.0), vec2(0.0, texel.y), useY);

  mediump vec3 p = texture(uUpscaled, uv0 + axis).rgb;
  mediump vec3 n = texture(uUpscaled, uv0 - axis).rgb;
  mediump float lp = fastLuma(p);
  mediump float ln = fastLuma(n);

  mediump float blur = 0.5 * (lp + ln);
  mediump float detail = lc - blur;
  mediump float ad = max(abs(detail) - (1.0 / 512.0), 0.0);
  detail = (detail < 0.0) ? -ad : ad;

  mediump float gMax = max(abs(dx), abs(dy));
  mediump float gMin = min(abs(dx), abs(dy));
  mediump float dirConfidence = (gMax - gMin) / (gMax + gMin + 1e-4);
  mediump float k = sharp * (1.18 + 0.22 * dirConfidence);
  mediump float outL = lc + detail * k;

  mediump float lo = min(lc, min(lp, ln));
  mediump float hi = max(lc, max(lp, ln));
  mediump float pad = 0.008 + 0.035 * sharp;
  outL = clamp(outL, lo - pad, hi + pad);

  mediump vec3 outc = c + vec3(outL - lc);
  fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fsr_sharpness_mapping_and_oes_conversion() {
        assert_eq!(map_ui_sharp_to_internal(0.0, false), 0.0);
        let rcas_s = map_ui_sharp_to_internal_rcas(0.5, 1, false);
        assert!(rcas_s > 0.0 && rcas_s <= 0.40);
        let easu_s = map_ui_sharp_to_internal_easu_rcas_linear(0.5, 1);
        assert!(easu_s > 0.5 && easu_s <= 1.0);

        let converted = fs_oes_to_2d(FS_EASU_QUALITY);
        assert!(!converted.contains("samplerExternalOES"));
        assert!(converted.contains("sampler2D uTex"));
    }
}
