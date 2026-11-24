#![allow(non_snake_case)]

pub mod control_plane;
pub mod controller_db;
pub mod cpu_affinity;
pub mod dsp_math;
pub mod jni_bridge;
pub mod jni_sys;
pub mod ndk_input;
pub mod ndk_sys;
pub mod protocol;
pub mod render;

use jni_sys::*;
use protocol::{input, stun, ServerInfo, StreamConfig};
use std::panic::{catch_unwind, AssertUnwindSafe};

#[inline]
fn with_jni_unwind<T, F: FnOnce() -> T>(default: T, f: F) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_init(
    env: *mut JNIEnv,
    clazz: jclass,
) {
    with_jni_unwind((), || {
        jni_bridge::init_bridge(env, clazz);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_startConnection(
    env: *mut JNIEnv,
    _clazz: jclass,
    address: jstring,
    app_version: jstring,
    gfe_version: jstring,
    rtsp_session_url: jstring,
    server_codec_mode_support: jint,
    width: jint,
    height: jint,
    fps: jint,
    bitrate: jint,
    packet_size: jint,
    streaming_remotely: jint,
    audio_configuration: jint,
    supported_video_formats: jint,
    client_refresh_rate_x100: jint,
    ri_aes_key: jbyteArray,
    ri_aes_iv: jbyteArray,
    video_capabilities: jint,
    color_space: jint,
    color_range: jint,
) -> jint {
    with_jni_unwind(-1, || {
        let addr_str = match jstring_to_string(env, address) {
            Some(s) => s,
            None => return -1,
        };
        let app_ver_str =
            jstring_to_string(env, app_version).unwrap_or_else(|| "7.1.431.0".to_string());
        let gfe_ver_str = jstring_to_string(env, gfe_version);
        let rtsp_url_str = jstring_to_string(env, rtsp_session_url);

        let key_vec = jbytearray_to_vec(env, ri_aes_key);
        let iv_vec = jbytearray_to_vec(env, ri_aes_iv);
        let mut key = [0u8; 16];
        let mut iv = [0u8; 16];
        let key_len = key_vec.len().min(16);
        let iv_len = iv_vec.len().min(16);
        key[..key_len].copy_from_slice(&key_vec[..key_len]);
        iv[..iv_len].copy_from_slice(&iv_vec[..iv_len]);

        let server_info = ServerInfo {
            address: addr_str,
            app_version: app_ver_str,
            gfe_version: gfe_ver_str,
            rtsp_session_url: rtsp_url_str,
            server_codec_mode_support,
        };

        let stream_config = StreamConfig {
            width,
            height,
            fps,
            bitrate,
            packet_size,
            streaming_remotely,
            audio_configuration,
            supported_video_formats,
            client_refresh_rate_x100,
            color_space,
            color_range,
            encryption_flags: 0x04, // SS_ENC_AUDIO (AES-128-CBC audio encryption)
            ri_aes_key: key,
            ri_aes_iv: iv,
            video_capabilities,
        };

        protocol::start_connection(server_info, stream_config)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_stopConnection(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        protocol::stop_connection();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_interruptConnection(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        protocol::interrupt_connection();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendExecServerCmd(
    _env: *mut JNIEnv,
    _clazz: jclass,
    cmd_id: jint,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_exec_server_cmd(cmd_id as u8);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendEmptyPayload(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        let _ = protocol::send_raw_input(&[0u8; 8]);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMouseMove(
    _env: *mut JNIEnv,
    _clazz: jclass,
    delta_x: jshort,
    delta_y: jshort,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_mouse_move_rel(delta_x, delta_y);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMousePosition(
    _env: *mut JNIEnv,
    _clazz: jclass,
    x: jshort,
    y: jshort,
    reference_width: jshort,
    reference_height: jshort,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_mouse_move_abs(x, y, reference_width, reference_height);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMouseMoveAsMousePosition(
    _env: *mut JNIEnv,
    _clazz: jclass,
    delta_x: jshort,
    delta_y: jshort,
    reference_width: jshort,
    reference_height: jshort,
) {
    with_jni_unwind((), || {
        let _ = protocol::send_mouse_move_as_position(
            delta_x,
            delta_y,
            reference_width,
            reference_height,
        );
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMouseButton(
    _env: *mut JNIEnv,
    _clazz: jclass,
    button_event: jbyte,
    mouse_button: jbyte,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_mouse_button(button_event, mouse_button);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMultiControllerInput(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jshort,
    active_gamepad_mask: jshort,
    button_flags: jint,
    left_trigger: jbyte,
    right_trigger: jbyte,
    left_stick_x: jshort,
    left_stick_y: jshort,
    right_stick_x: jshort,
    right_stick_y: jshort,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_multi_controller(
            controller_number,
            active_gamepad_mask,
            button_flags,
            left_trigger,
            right_trigger,
            left_stick_x,
            left_stick_y,
            right_stick_x,
            right_stick_y,
        );
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendTouchEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    event_type: jbyte,
    pointer_id: jint,
    x: jfloat,
    y: jfloat,
    pressure: jfloat,
    contact_area_major: jfloat,
    contact_area_minor: jfloat,
    rotation: jshort,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt = input::serialize_touch_event(
            event_type as u8,
            pointer_id as u32,
            x,
            y,
            pressure,
            contact_area_major,
            contact_area_minor,
            rotation as u16,
        );
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendPenEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    event_type: jbyte,
    tool_type: jbyte,
    pen_buttons: jbyte,
    x: jfloat,
    y: jfloat,
    pressure: jfloat,
    contact_area_major: jfloat,
    contact_area_minor: jfloat,
    rotation: jshort,
    tilt: jbyte,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt = input::serialize_pen_event(
            event_type as u8,
            tool_type as u8,
            pen_buttons as u8,
            x,
            y,
            pressure,
            contact_area_major,
            contact_area_minor,
            rotation as u16,
            tilt as u8,
        );
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendControllerArrivalEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jbyte,
    active_gamepad_mask: jshort,
    ctype: jbyte,
    supported_button_flags: jint,
    capabilities: jshort,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt = input::serialize_controller_arrival(
            controller_number as u8,
            active_gamepad_mask as u16,
            ctype as u8,
            supported_button_flags as u32,
            capabilities as u16,
        );
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendControllerTouchEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jbyte,
    event_type: jbyte,
    pointer_id: jint,
    x: jfloat,
    y: jfloat,
    pressure: jfloat,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt = input::serialize_controller_touch(
            controller_number as u8,
            event_type as u8,
            pointer_id as u32,
            x,
            y,
            pressure,
        );
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendControllerMotionEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jbyte,
    motion_type: jbyte,
    x: jfloat,
    y: jfloat,
    z: jfloat,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt =
            input::serialize_controller_motion(controller_number as u8, motion_type as u8, x, y, z);
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendControllerBatteryEvent(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jbyte,
    battery_state: jbyte,
    battery_percentage: jbyte,
) -> jint {
    with_jni_unwind(-1, || {
        let pkt = input::serialize_controller_battery(
            controller_number as u8,
            battery_state as u8,
            battery_percentage as u8,
        );
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendKeyboardInput(
    _env: *mut JNIEnv,
    _clazz: jclass,
    key_code: jshort,
    key_action: jbyte,
    modifiers: jbyte,
    flags: jbyte,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_keyboard_event(key_code, key_action, modifiers, flags);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMouseHighResScroll(
    _env: *mut JNIEnv,
    _clazz: jclass,
    scroll_amount: jshort,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_high_res_scroll(scroll_amount);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendMouseHighResHScroll(
    _env: *mut JNIEnv,
    _clazz: jclass,
    scroll_amount: jshort,
) {
    with_jni_unwind((), || {
        let pkt = input::serialize_high_res_hscroll(scroll_amount);
        let _ = protocol::send_raw_input(&pkt);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendUtf8Text(
    env: *mut JNIEnv,
    _clazz: jclass,
    text: jstring,
) {
    with_jni_unwind((), || {
        if let Some(s) = jstring_to_string(env, text) {
            let pkt = input::serialize_utf8_text(&s);
            let _ = protocol::send_raw_input(&pkt);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getStageName(
    env: *mut JNIEnv,
    _clazz: jclass,
    stage: jint,
) -> jstring {
    with_jni_unwind(std::ptr::null_mut(), || {
        string_to_jstring(env, protocol::get_stage_name(stage))
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_findExternalAddressIP4(
    env: *mut JNIEnv,
    _clazz: jclass,
    stun_host_name: jstring,
    stun_port: jint,
) -> jstring {
    with_jni_unwind(std::ptr::null_mut(), || {
        let host = match jstring_to_string(env, stun_host_name) {
            Some(h) => h,
            None => return std::ptr::null_mut(),
        };
        match stun::find_external_address_ipv4(&host, stun_port as u16) {
            Some(ip) => string_to_jstring(env, &ip.to_string()),
            None => std::ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getPendingAudioDuration(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, protocol::get_pending_audio_duration)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getPendingVideoFrames(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, protocol::get_pending_video_frames)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_testClientConnectivity(
    env: *mut JNIEnv,
    _clazz: jclass,
    test_server_host_name: jstring,
    reference_port: jint,
    test_flags: jint,
) -> jint {
    with_jni_unwind(protocol::VB_TEST_RESULT_INCONCLUSIVE as jint, || {
        let host = match jstring_to_string(env, test_server_host_name) {
            Some(h) => h,
            None => return protocol::VB_TEST_RESULT_INCONCLUSIVE as jint,
        };
        protocol::test_client_connectivity(&host, reference_port as u16, test_flags as u32) as jint
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getPortFlagsFromStage(
    _env: *mut JNIEnv,
    _clazz: jclass,
    stage: jint,
) -> jint {
    with_jni_unwind(0, || protocol::get_port_flags_from_stage(stage) as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getPortFlagsFromTerminationErrorCode(
    _env: *mut JNIEnv,
    _clazz: jclass,
    error_code: jint,
) -> jint {
    with_jni_unwind(0, || {
        protocol::get_port_flags_from_termination_error_code(error_code) as jint
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_stringifyPortFlags(
    env: *mut JNIEnv,
    _clazz: jclass,
    port_flags: jint,
    separator: jstring,
) -> jstring {
    with_jni_unwind(std::ptr::null_mut(), || {
        let sep = jstring_to_string(env, separator).unwrap_or_default();
        let out = protocol::stringify_port_flags(port_flags as u32, &sep);
        string_to_jstring(env, &out)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getEstimatedRttInfo(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jlong {
    with_jni_unwind(-1, protocol::get_estimated_rtt_info)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getLaunchUrlQueryParameters(
    env: *mut JNIEnv,
    _clazz: jclass,
) -> jstring {
    with_jni_unwind(std::ptr::null_mut(), || {
        string_to_jstring(env, protocol::get_launch_url_query_parameters())
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_guessControllerType(
    _env: *mut JNIEnv,
    _clazz: jclass,
    vendor_id: jint,
    product_id: jint,
) -> jbyte {
    with_jni_unwind(-1, || controller_db::guess_controller_type(vendor_id, product_id))
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_guessControllerHasPaddles(
    _env: *mut JNIEnv,
    _clazz: jclass,
    vendor_id: jint,
    product_id: jint,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        if controller_db::guess_controller_has_paddles(vendor_id, product_id) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_guessControllerHasShareButton(
    _env: *mut JNIEnv,
    _clazz: jclass,
    vendor_id: jint,
    product_id: jint,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        if controller_db::guess_controller_has_share_button(vendor_id, product_id) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendControllerSpeakerOwner(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jshort,
) -> jint {
    with_jni_unwind(-1, || {
        let owner = protocol::next_controller_speaker_owner();
        let pkt = input::serialize_controller_speaker_owner_binding(controller_number as u16, owner);
        protocol::send_raw_input(&pkt)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_requestIdrFrame(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        protocol::request_idr_frame();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_processAnalogStickRadial(
    _env: *mut JNIEnv,
    _clazz: jclass,
    x: jfloat,
    y: jfloat,
    deadzone_radius: jfloat,
) -> jint {
    with_jni_unwind(0, || {
        dsp_math::process_analog_stick_radial(x, y, deadzone_radius, 1.0)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_computeFsrRcasSharpness(
    _env: *mut JNIEnv,
    _clazz: jclass,
    ui_sharpness: jfloat,
    preset: jint,
    near_native: jboolean,
) -> jfloat {
    with_jni_unwind(0.0, || {
        dsp_math::compute_fsr_rcas_sharpness(ui_sharpness, preset, near_native != JNI_FALSE)
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_pinCurrentThreadToBigCores(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        cpu_affinity::pin_current_thread_to_big_cores();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_pinHotThreadsToBigCores(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, || cpu_affinity::pin_hot_threads_to_big_cores() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getPrimeCoreCount(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, || cpu_affinity::get_prime_core_count() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getBigCoreCount(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, || cpu_affinity::get_big_core_count() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_getLittleCoreCount(
    _env: *mut JNIEnv,
    _clazz: jclass,
) -> jint {
    with_jni_unwind(0, || cpu_affinity::get_little_core_count() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_pinCurrentThreadToCoreSet(
    env: *mut JNIEnv,
    _clazz: jclass,
    core_set: jstring,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        let cs = unsafe { jstring_to_string(env, core_set) }.unwrap_or_else(|| "all".to_string());
        if cpu_affinity::pin_current_thread_to_core_set(&cs) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_patchH264Sps(
    env: *mut JNIEnv,
    _clazz: jclass,
    sps_data: jbyteArray,
    width: jint,
    height: jint,
    refresh_rate: jint,
    ref_frame_invalidation_active: jboolean,
    needs_baseline_hack: jboolean,
    constrained_high_profile: jboolean,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let raw = unsafe { jbytearray_to_vec(env, sps_data) };
        let patched = protocol::video::patch_h264_sps(
            &raw,
            width,
            height,
            refresh_rate,
            ref_frame_invalidation_active != JNI_FALSE,
            needs_baseline_hack != JNI_FALSE,
            constrained_high_profile != JNI_FALSE,
        );
        unsafe { slice_to_jbytearray(env, &patched) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_encryptAes128Ecb(
    env: *mut JNIEnv,
    _clazz: jclass,
    plaintext: jbyteArray,
    key: jbyteArray,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let data = unsafe { jbytearray_to_vec(env, plaintext) };
        let key_vec = unsafe { jbytearray_to_vec(env, key) };
        let mut key_arr = [0u8; 16];
        let copy_len = key_vec.len().min(16);
        key_arr[..copy_len].copy_from_slice(&key_vec[..copy_len]);
        let cipher = protocol::crypto::Aes128::new(&key_arr);
        let encrypted = cipher.encrypt_ecb(&data);
        unsafe { slice_to_jbytearray(env, &encrypted) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_decryptAes128Ecb(
    env: *mut JNIEnv,
    _clazz: jclass,
    ciphertext: jbyteArray,
    key: jbyteArray,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let data = unsafe { jbytearray_to_vec(env, ciphertext) };
        let key_vec = unsafe { jbytearray_to_vec(env, key) };
        let mut key_arr = [0u8; 16];
        let copy_len = key_vec.len().min(16);
        key_arr[..copy_len].copy_from_slice(&key_vec[..copy_len]);
        let cipher = protocol::crypto::Aes128::new(&key_arr);
        let decrypted = cipher.decrypt_ecb(&data);
        unsafe { slice_to_jbytearray(env, &decrypted) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_buildTbsCertificateDer(
    env: *mut JNIEnv,
    _clazz: jclass,
    spki_der: jbyteArray,
    serial_bytes: jbyteArray,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let spki = unsafe { jbytearray_to_vec(env, spki_der) };
        let serial = unsafe { jbytearray_to_vec(env, serial_bytes) };
        let tbs = protocol::crypto::build_tbs_certificate_der(&spki, &serial);
        unsafe { slice_to_jbytearray(env, &tbs) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_buildX509CertificatePem(
    env: *mut JNIEnv,
    _clazz: jclass,
    tbs_der: jbyteArray,
    signature_bytes: jbyteArray,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let tbs = unsafe { jbytearray_to_vec(env, tbs_der) };
        let sig = unsafe { jbytearray_to_vec(env, signature_bytes) };
        let pem = protocol::crypto::build_x509_certificate_pem(&tbs, &sig);
        unsafe { slice_to_jbytearray(env, &pem) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_sendWolPacket(
    env: *mut JNIEnv,
    _clazz: jclass,
    mac_address: jstring,
    host_address: jstring,
    http_port: jint,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        let mac = unsafe { jstring_to_string(env, mac_address) }.unwrap_or_default();
        let host = unsafe { jstring_to_string(env, host_address) }.unwrap_or_default();
        let port = if http_port > 0 { http_port as u16 } else { 47989 };
        if protocol::wol::send_wol_packet(&mac, &host, port) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_setVideoSurface(
    env: *mut JNIEnv,
    _clazz: jclass,
    surface: jobject,
) {
    with_jni_unwind((), || {
        #[cfg(target_os = "android")]
        unsafe {
            let win = if surface.is_null() {
                std::ptr::null_mut()
            } else {
                ndk_sys::ANativeWindow_fromSurface(env, surface)
            };
            render::ndk_video::set_native_video_surface(win);
        }
        #[cfg(not(target_os = "android"))]
        let _ = (env, surface);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_setFsrConfig(
    _env: *mut JNIEnv,
    _clazz: jclass,
    enabled: jboolean,
    mode: jint,
    preset: jint,
    sharpness: jint,
    hdr_active: jboolean,
) {
    with_jni_unwind((), || {
        render::ndk_video::update_fsr_config(
            enabled != JNI_FALSE,
            mode,
            preset,
            sharpness,
            hdr_active != JNI_FALSE,
        );
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_startNdkMotionSensors(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jbyte,
    rate_hz: jshort,
) {
    with_jni_unwind((), || {
        ndk_input::start_ndk_device_motion_sensors(controller_number as u8, rate_hz as u16);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_stopNdkMotionSensors(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        ndk_input::stop_ndk_device_motion_sensors();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_submitPrimitiveGamepadState(
    _env: *mut JNIEnv,
    _clazz: jclass,
    controller_number: jshort,
    active_gamepad_mask: jshort,
    button_flags: jint,
    left_trigger: jfloat,
    right_trigger: jfloat,
    left_x: jfloat,
    left_y: jfloat,
    right_x: jfloat,
    right_y: jfloat,
) {
    with_jni_unwind((), || {
        ndk_input::submit_primitive_gamepad_state(
            controller_number,
            active_gamepad_mask,
            button_flags,
            left_trigger,
            right_trigger,
            left_x,
            left_y,
            right_x,
            right_y,
        );
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_submitPrimitiveTrackpadDelta(
    _env: *mut JNIEnv,
    _clazz: jclass,
    raw_dx: jfloat,
    raw_dy: jfloat,
) {
    with_jni_unwind((), || {
        ndk_input::submit_primitive_trackpad_delta(raw_dx, raw_dy);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_mdnsStartDiscovery(
    _env: *mut JNIEnv,
    _clazz: jclass,
    interval_ms: jint,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        if protocol::mdns::start_mdns_discovery(interval_ms.max(500) as u32) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_mdnsStopDiscovery(
    _env: *mut JNIEnv,
    _clazz: jclass,
) {
    with_jni_unwind((), || {
        protocol::mdns::stop_mdns_discovery();
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_mdnsDrainDiscoveredHosts(
    env: *mut JNIEnv,
    _clazz: jclass,
) -> jstring {
    with_jni_unwind(std::ptr::null_mut(), || {
        let encoded = protocol::mdns::drain_mdns_discovered_encoded();
        unsafe { string_to_jstring(env, &encoded) }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_verifyPinnedCertSha256(
    env: *mut JNIEnv,
    _clazz: jclass,
    cert_der: jbyteArray,
    expected_sha256: jbyteArray,
) -> jboolean {
    with_jni_unwind(JNI_FALSE, || {
        let cert = unsafe { jbytearray_to_vec(env, cert_der) };
        let expected = unsafe { jbytearray_to_vec(env, expected_sha256) };
        if expected.len() != 32 {
            return JNI_FALSE;
        }
        let mut fp = [0u8; 32];
        fp.copy_from_slice(&expected);
        if control_plane::verify_pinned_cert_sha256(&cert, &fp) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_ineedmypills_vibemis_nvstream_jni_VibemisBridge_derivePairingAesKey(
    env: *mut JNIEnv,
    _clazz: jclass,
    salt_16: jbyteArray,
    pin_ascii: jstring,
) -> jbyteArray {
    with_jni_unwind(std::ptr::null_mut(), || {
        let salt = unsafe { jbytearray_to_vec(env, salt_16) };
        if salt.len() != 16 {
            return std::ptr::null_mut();
        }
        let pin = unsafe { jstring_to_string(env, pin_ascii) }.unwrap_or_default();
        let mut salt_arr = [0u8; 16];
        salt_arr.copy_from_slice(&salt);
        let key = control_plane::derive_pairing_aes_key(&salt_arr, &pin);
        unsafe { slice_to_jbytearray(env, &key) }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_controller_db_lookup() {
        // Xbox Series X controller (0x045e, 0x0b12)
        assert_eq!(
            controller_db::guess_controller_type(0x045e, 0x0b12),
            controller_db::VB_CTYPE_XBOX
        );
        assert!(controller_db::guess_controller_has_share_button(0x045e, 0x0b12));
        // DualSense Edge (0x054c, 0x0df2)
        assert!(controller_db::guess_controller_has_paddles(0x054c, 0x0df2));
    }
}
