/*
 * Liquid-glass effects adapted from KernelSU Manager and
 * Kyant0/AndroidLiquidGlass (Apache License 2.0).
 */
package com.adguardhome.android.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

fun BackdropEffectScope.vibrancy() {
    colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f)
}

fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || refractionAmount <= 0f) return
    padding = maxOf(padding, refractionAmount)
    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    runtimeShaderEffect(key = "AdGuardLiquidLens", shaderString = LENS_SHADER, uniformShaderName = "content") {
        setFloatUniform("size", size.width / sf, size.height / sf)
        setFloatUniform("offset", -padding / sf, -padding / sf)
        setFloatUniform("height", refractionHeight / sf)
        setFloatUniform("amount", refractionAmount / sf)
        setFloatUniform("depth", if (depthEffect) 1f else 0f)
        setFloatUniform("dispersion", chromaticAberration)
    }
}

private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float height;
uniform float amount;
uniform float depth;
uniform float dispersion;
half4 main(float2 coord) {
    float2 center = size * 0.5;
    float2 p = coord + offset - center;
    float2 halfSize = center;
    float radius = min(halfSize.x, halfSize.y);
    float2 q = abs(p) - (halfSize - radius);
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float edge = clamp(1.0 + distance / max(height, 0.001), 0.0, 1.0);
    float2 direction = normalize(p + float2(0.0001));
    float2 shift = direction * amount * edge * edge * (0.75 + 0.25 * depth);
    float2 samplePoint = coord + shift;
    if (dispersion <= 0.0) return content.eval(samplePoint);
    float2 colorShift = direction * dispersion * edge * 2.0;
    half4 base = content.eval(samplePoint);
    half4 red = content.eval(samplePoint + colorShift);
    half4 blue = content.eval(samplePoint - colorShift);
    return half4(red.r, base.g, blue.b, base.a);
}
"""
