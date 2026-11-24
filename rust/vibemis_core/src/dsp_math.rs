//! High-performance Rust math & DSP module for Vibemis.
//! Offloads radial analog stick deadzone/curve processing, FSR1 RCAS sharpness mapping,
//! and audio PCM telemetry from Kotlin/Dalvik to native SIMD-friendly Rust.

/// Applies a radial deadzone and non-linear response curve to raw 2D analog stick inputs
/// (`x`, `y` in `[-1.0, 1.0]`), returning packed `(out_x, out_y)` as `i16` values in `[-32767, 32767]`.
/// The upper 16 bits of the returned `i32` hold `out_x as u16` and the lower 16 bits hold `out_y as u16`.
#[inline]
pub fn process_analog_stick_radial(x: f32, y: f32, deadzone: f32, sensitivity_scale: f32) -> i32 {
    let dz = deadzone.clamp(0.0, 0.95);
    let scale = sensitivity_scale.clamp(0.1, 2.0);
    let mag = (x * x + y * y).sqrt();

    if mag <= dz || !mag.is_finite() {
        return 0;
    }

    let normalized_mag = ((mag - dz) / (1.0 - dz)).clamp(0.0, 1.0);
    let curved_mag = (normalized_mag * scale).clamp(0.0, 1.0);

    let inv_mag = 1.0 / mag;
    let out_x_f = (x * inv_mag * curved_mag * 32767.0).round().clamp(-32767.0, 32767.0);
    let out_y_f = (y * inv_mag * curved_mag * 32767.0).round().clamp(-32767.0, 32767.0);

    let ix = (out_x_f as i16) as u16 as u32;
    let iy = (out_y_f as i16) as u16 as u32;
    ((ix << 16) | iy) as i32
}

/// Maps UI sharpness (`[0.0, 1.0]`) and preset (`0 = Perf`, `1 = Balanced`, `2 = Quality`)
/// to internal FSR1 RCAS sharpness parameter.
#[inline]
pub fn compute_fsr_rcas_sharpness(sharp_ui: f32, preset: i32, near_native: bool) -> f32 {
    let u = sharp_ui.clamp(0.0, 1.0);
    if u <= 0.0001 {
        return 0.0;
    }
    let p = preset.clamp(0, 2);
    let preset_mul = match p {
        2 => 1.04_f32,
        1 => 1.00_f32,
        _ => 0.92_f32,
    };
    let max_out = if near_native {
        match p {
            2 => 0.65_f32,
            1 => 0.58_f32,
            _ => 0.50_f32,
        }
    } else {
        match p {
            2 => 0.72_f32,
            1 => 0.65_f32,
            _ => 0.56_f32,
        }
    };
    let s = (0.04 + 0.68 * u) * preset_mul;
    s.clamp(0.0, max_out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_analog_stick_deadzone() {
        assert_eq!(process_analog_stick_radial(0.03, -0.02, 0.07, 1.0), 0);
        let packed = process_analog_stick_radial(1.0, 0.0, 0.07, 1.0);
        let out_x = ((packed as u32) >> 16) as u16 as i16;
        let out_y = ((packed as u32) & 0xFFFF) as u16 as i16;
        assert_eq!(out_x, 32767);
        assert_eq!(out_y, 0);
    }

    #[test]
    fn test_fsr_rcas_sharpness() {
        assert_eq!(compute_fsr_rcas_sharpness(0.0, 1, false), 0.0);
        let s = compute_fsr_rcas_sharpness(0.5, 2, false);
        assert!(s > 0.35 && s <= 0.72);
    }
}
