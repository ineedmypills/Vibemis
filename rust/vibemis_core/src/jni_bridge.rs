//! Safe-ish Rust wrapper around the `VibemisBridge` JVM static callback methods.

use crate::jni_sys::*;
use std::cell::RefCell;
use std::ffi::{c_void, CString};
use std::sync::atomic::{AtomicUsize, Ordering};

static JVM_PTR: AtomicUsize = AtomicUsize::new(0);
static BRIDGE_CLASS: AtomicUsize = AtomicUsize::new(0);

static M_DR_SETUP: AtomicUsize = AtomicUsize::new(0);
static M_DR_START: AtomicUsize = AtomicUsize::new(0);
static M_DR_STOP: AtomicUsize = AtomicUsize::new(0);
static M_DR_CLEANUP: AtomicUsize = AtomicUsize::new(0);
static M_DR_SUBMIT: AtomicUsize = AtomicUsize::new(0);

static M_CL_STAGE_STARTING: AtomicUsize = AtomicUsize::new(0);
static M_CL_STAGE_COMPLETE: AtomicUsize = AtomicUsize::new(0);
static M_CL_STAGE_FAILED: AtomicUsize = AtomicUsize::new(0);
static M_CL_CONN_STARTED: AtomicUsize = AtomicUsize::new(0);
static M_CL_CONN_TERMINATED: AtomicUsize = AtomicUsize::new(0);
static M_CL_RUMBLE: AtomicUsize = AtomicUsize::new(0);
static M_CL_CONN_STATUS: AtomicUsize = AtomicUsize::new(0);
static M_CL_SET_HDR: AtomicUsize = AtomicUsize::new(0);
static M_CL_RUMBLE_TRIGGERS: AtomicUsize = AtomicUsize::new(0);
static M_CL_SET_MOTION_STATE: AtomicUsize = AtomicUsize::new(0);
static M_CL_SET_LED: AtomicUsize = AtomicUsize::new(0);
static M_CL_CONTROLLER_SPEAKER_PCM: AtomicUsize = AtomicUsize::new(0);

unsafe fn get_static_method(env: *mut JNIEnv, clazz: jclass, name: &str, sig: &str) -> usize {
    let cname = CString::new(name).unwrap();
    let csig = CString::new(sig).unwrap();
    let mid = ((**env).GetStaticMethodID)(env, clazz, cname.as_ptr(), csig.as_ptr());
    if ((**env).ExceptionCheck)(env) == JNI_TRUE {
        ((**env).ExceptionClear)(env);
        return 0;
    }
    mid as usize
}

pub unsafe fn init_bridge(env: *mut JNIEnv, clazz: jclass) {
    if env.is_null() || clazz.is_null() {
        return;
    }
    let mut vm: *mut JavaVM = std::ptr::null_mut();
    if ((**env).GetJavaVM)(env, &mut vm) == JNI_OK && !vm.is_null() {
        JVM_PTR.store(vm as usize, Ordering::SeqCst);
    }

    let c_class_name = CString::new("com/ineedmypills/vibemis/nvstream/jni/VibemisBridge").unwrap();
    let found = ((**env).FindClass)(env, c_class_name.as_ptr());
    let target_class = if !found.is_null() { found } else { clazz };
    let gref = ((**env).NewGlobalRef)(env, target_class);
    BRIDGE_CLASS.store(gref as usize, Ordering::SeqCst);

    M_DR_SETUP.store(get_static_method(env, clazz, "bridgeDrSetup", "(IIII)I"), Ordering::SeqCst);
    M_DR_START.store(get_static_method(env, clazz, "bridgeDrStart", "()V"), Ordering::SeqCst);
    M_DR_STOP.store(get_static_method(env, clazz, "bridgeDrStop", "()V"), Ordering::SeqCst);
    M_DR_CLEANUP.store(get_static_method(env, clazz, "bridgeDrCleanup", "()V"), Ordering::SeqCst);
    M_DR_SUBMIT.store(
        get_static_method(env, clazz, "bridgeDrSubmitDecodeUnit", "([BIIIICJJ)I"),
        Ordering::SeqCst,
    );

    M_CL_STAGE_STARTING.store(
        get_static_method(env, clazz, "bridgeClStageStarting", "(I)V"),
        Ordering::SeqCst,
    );
    M_CL_STAGE_COMPLETE.store(
        get_static_method(env, clazz, "bridgeClStageComplete", "(I)V"),
        Ordering::SeqCst,
    );
    M_CL_STAGE_FAILED.store(
        get_static_method(env, clazz, "bridgeClStageFailed", "(II)V"),
        Ordering::SeqCst,
    );
    M_CL_CONN_STARTED.store(
        get_static_method(env, clazz, "bridgeClConnectionStarted", "()V"),
        Ordering::SeqCst,
    );
    M_CL_CONN_TERMINATED.store(
        get_static_method(env, clazz, "bridgeClConnectionTerminated", "(I)V"),
        Ordering::SeqCst,
    );
    M_CL_RUMBLE.store(get_static_method(env, clazz, "bridgeClRumble", "(SSS)V"), Ordering::SeqCst);
    M_CL_CONN_STATUS.store(
        get_static_method(env, clazz, "bridgeClConnectionStatusUpdate", "(I)V"),
        Ordering::SeqCst,
    );
    M_CL_SET_HDR.store(
        get_static_method(env, clazz, "bridgeClSetHdrMode", "(Z[B)V"),
        Ordering::SeqCst,
    );
    M_CL_RUMBLE_TRIGGERS.store(
        get_static_method(env, clazz, "bridgeClRumbleTriggers", "(SSS)V"),
        Ordering::SeqCst,
    );
    M_CL_SET_MOTION_STATE.store(
        get_static_method(env, clazz, "bridgeClSetMotionEventState", "(SBS)V"),
        Ordering::SeqCst,
    );
    M_CL_SET_LED.store(
        get_static_method(env, clazz, "bridgeClSetControllerLED", "(SBBB)V"),
        Ordering::SeqCst,
    );
    M_CL_CONTROLLER_SPEAKER_PCM.store(
        get_static_method(env, clazz, "bridgeClControllerSpeakerPcm", "(S[B)V"),
        Ordering::SeqCst,
    );
}

struct JniThreadState {
    vm: *mut JavaVM,
    attached_by_us: bool,
    scratch_jarr: jbyteArray,
    scratch_cap: usize,
}

impl Drop for JniThreadState {
    fn drop(&mut self) {
        if self.vm.is_null() {
            return;
        }
        unsafe {
            if !self.scratch_jarr.is_null() {
                let mut env_void: *mut c_void = std::ptr::null_mut();
                if ((**self.vm).GetEnv)(self.vm, &mut env_void, JNI_VERSION_1_6) == JNI_OK
                    && !env_void.is_null()
                {
                    let env = env_void as *mut JNIEnv;
                    ((**env).DeleteGlobalRef)(env, self.scratch_jarr);
                }
                self.scratch_jarr = std::ptr::null_mut();
                self.scratch_cap = 0;
            }
            if self.attached_by_us {
                let _ = ((**self.vm).DetachCurrentThread)(self.vm);
                self.attached_by_us = false;
            }
        }
    }
}

thread_local! {
    static THREAD_JNI_STATE: RefCell<Option<JniThreadState>> = const { RefCell::new(None) };
}

unsafe fn with_thread_env<F, R>(default: R, f: F) -> R
where
    F: FnOnce(*mut JNIEnv, jclass) -> R,
{
    let vm = JVM_PTR.load(Ordering::Relaxed) as *mut JavaVM;
    let clazz = BRIDGE_CLASS.load(Ordering::Relaxed) as jclass;
    if vm.is_null() || clazz.is_null() {
        return default;
    }

    let mut env_void: *mut c_void = std::ptr::null_mut();
    if ((**vm).GetEnv)(vm, &mut env_void, JNI_VERSION_1_6) != JNI_OK || env_void.is_null() {
        let mut env_ptr: *mut JNIEnv = std::ptr::null_mut();
        if ((**vm).AttachCurrentThread)(vm, &mut env_ptr, std::ptr::null_mut()) != JNI_OK
            || env_ptr.is_null()
        {
            return default;
        }
        env_void = env_ptr as *mut c_void;
        THREAD_JNI_STATE.with(|slot| {
            *slot.borrow_mut() = Some(JniThreadState {
                vm,
                attached_by_us: true,
                scratch_jarr: std::ptr::null_mut(),
                scratch_cap: 0,
            });
        });
    } else {
        THREAD_JNI_STATE.with(|slot| {
            let mut borrow = slot.borrow_mut();
            if borrow.is_none() {
                *borrow = Some(JniThreadState {
                    vm,
                    attached_by_us: false,
                    scratch_jarr: std::ptr::null_mut(),
                    scratch_cap: 0,
                });
            }
        });
    }

    let env = env_void as *mut JNIEnv;
    let result = f(env, clazz);
    if ((**env).ExceptionCheck)(env) == JNI_TRUE {
        ((**env).ExceptionClear)(env);
    }
    result
}

unsafe fn with_reusable_byte_array<F, R>(env: *mut JNIEnv, data: &[u8], default: R, f: F) -> R
where
    F: FnOnce(jbyteArray) -> R,
{
    let vm = JVM_PTR.load(Ordering::Relaxed) as *mut JavaVM;
    let needed = data.len();
    let reused: Option<jbyteArray> = THREAD_JNI_STATE.with(|slot| {
        let mut borrow = slot.borrow_mut();
        let state = borrow.get_or_insert(JniThreadState {
            vm,
            attached_by_us: false,
            scratch_jarr: std::ptr::null_mut(),
            scratch_cap: 0,
        });
        if state.scratch_jarr.is_null() || state.scratch_cap < needed {
            if !state.scratch_jarr.is_null() {
                ((**env).DeleteGlobalRef)(env, state.scratch_jarr);
                state.scratch_jarr = std::ptr::null_mut();
                state.scratch_cap = 0;
            }
            // Grow to 64KB minimum or next 64KB multiple to avoid frequent reallocations on I-frames
            let alloc_cap = needed.max(64 * 1024).next_multiple_of(64 * 1024);
            let local = ((**env).NewByteArray)(env, alloc_cap as jsize);
            if local.is_null() {
                return None;
            }
            let global = ((**env).NewGlobalRef)(env, local);
            ((**env).DeleteLocalRef)(env, local);
            if global.is_null() {
                return None;
            }
            state.scratch_jarr = global;
            state.scratch_cap = alloc_cap;
        }
        Some(state.scratch_jarr)
    });

    let jarr = match reused {
        Some(a) => a,
        None => return default,
    };
    if needed > 0 {
        ((**env).SetByteArrayRegion)(env, jarr, 0, needed as jsize, data.as_ptr() as *const jbyte);
    }
    f(jarr)
}

pub fn bridge_dr_setup(video_format: i32, width: i32, height: i32, redraw_rate: i32) -> i32 {
    unsafe {
        with_thread_env(0, |env, clazz| {
            let mid = M_DR_SETUP.load(Ordering::Relaxed) as jmethodID;
            if mid.is_null() {
                return 0;
            }
            let args = [
                jvalue { i: video_format },
                jvalue { i: width },
                jvalue { i: height },
                jvalue { i: redraw_rate },
            ];
            ((**env).CallStaticIntMethodA)(env, clazz, mid, args.as_ptr())
        })
    }
}

pub fn bridge_dr_start() {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_DR_START.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, std::ptr::null());
            }
        });
    }
}

pub fn bridge_dr_stop() {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_DR_STOP.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, std::ptr::null());
            }
        });
    }
}

pub fn bridge_dr_cleanup() {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_DR_CLEANUP.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, std::ptr::null());
            }
        });
    }
}

pub fn bridge_dr_submit_decode_unit(
    data: &[u8],
    decode_unit_type: i32,
    frame_number: i32,
    frame_type: i32,
    host_latency: u16,
    receive_time_ms: i64,
    enqueue_time_ms: i64,
) -> i32 {
    unsafe {
        with_thread_env(0, |env, clazz| {
            let mid = M_DR_SUBMIT.load(Ordering::Relaxed) as jmethodID;
            if mid.is_null() {
                return 0;
            }
            with_reusable_byte_array(env, data, 0, |jarr| {
                let args = [
                    jvalue { l: jarr },
                    jvalue { i: data.len() as jint },
                    jvalue { i: decode_unit_type },
                    jvalue { i: frame_number },
                    jvalue { i: frame_type },
                    jvalue { c: host_latency },
                    jvalue { j: receive_time_ms },
                    jvalue { j: enqueue_time_ms },
                ];
                ((**env).CallStaticIntMethodA)(env, clazz, mid, args.as_ptr())
            })
        })
    }
}

pub fn bridge_cl_stage_starting(stage: i32) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_STAGE_STARTING.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [jvalue { i: stage }];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_stage_complete(stage: i32) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_STAGE_COMPLETE.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [jvalue { i: stage }];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_stage_failed(stage: i32, error_code: i32) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_STAGE_FAILED.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [jvalue { i: stage }, jvalue { i: error_code }];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_connection_started() {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_CONN_STARTED.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, std::ptr::null());
            }
        });
    }
}

pub fn bridge_cl_connection_terminated(error_code: i32) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_CONN_TERMINATED.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [jvalue { i: error_code }];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_rumble(controller_number: i16, low_freq: i16, high_freq: i16) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_RUMBLE.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [
                    jvalue { s: controller_number },
                    jvalue { s: low_freq },
                    jvalue { s: high_freq },
                ];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_connection_status_update(connection_status: i32) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_CONN_STATUS.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [jvalue { i: connection_status }];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_set_hdr_mode(enabled: bool, hdr_metadata: Option<&[u8]>) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_SET_HDR.load(Ordering::Relaxed) as jmethodID;
            if mid.is_null() {
                return;
            }
            let jarr = if let Some(meta) = hdr_metadata {
                let arr = ((**env).NewByteArray)(env, meta.len() as jsize);
                if !arr.is_null() {
                    ((**env).SetByteArrayRegion)(env, arr, 0, meta.len() as jsize, meta.as_ptr() as *const jbyte);
                }
                arr
            } else {
                std::ptr::null_mut()
            };
            let args = [
                jvalue { z: if enabled { JNI_TRUE } else { JNI_FALSE } },
                jvalue { l: jarr },
            ];
            ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            if !jarr.is_null() {
                ((**env).DeleteLocalRef)(env, jarr);
            }
        });
    }
}

pub fn bridge_cl_rumble_triggers(controller_number: i16, left: i16, right: i16) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_RUMBLE_TRIGGERS.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [
                    jvalue { s: controller_number },
                    jvalue { s: left },
                    jvalue { s: right },
                ];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_set_motion_event_state(controller_number: i16, motion_type: u8, report_rate: i16) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_SET_MOTION_STATE.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [
                    jvalue { s: controller_number },
                    jvalue { b: motion_type as jbyte },
                    jvalue { s: report_rate },
                ];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_set_controller_led(controller_number: i16, r: u8, g: u8, b: u8) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_SET_LED.load(Ordering::Relaxed) as jmethodID;
            if !mid.is_null() {
                let args = [
                    jvalue { s: controller_number },
                    jvalue { b: r as jbyte },
                    jvalue { b: g as jbyte },
                    jvalue { b: b as jbyte },
                ];
                ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            }
        });
    }
}

pub fn bridge_cl_controller_speaker_pcm(controller_number: i16, pcm_data: &[u8]) {
    unsafe {
        with_thread_env((), |env, clazz| {
            let mid = M_CL_CONTROLLER_SPEAKER_PCM.load(Ordering::Relaxed) as jmethodID;
            if mid.is_null() || pcm_data.is_empty() {
                return;
            }
            let jarr = ((**env).NewByteArray)(env, pcm_data.len() as jsize);
            if jarr.is_null() {
                return;
            }
            ((**env).SetByteArrayRegion)(env, jarr, 0, pcm_data.len() as jsize, pcm_data.as_ptr() as *const jbyte);
            let args = [
                jvalue { s: controller_number },
                jvalue { l: jarr },
            ];
            ((**env).CallStaticVoidMethodA)(env, clazz, mid, args.as_ptr());
            ((**env).DeleteLocalRef)(env, jarr);
        });
    }
}

