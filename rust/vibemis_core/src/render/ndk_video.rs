//! Native Android NDK `AMediaCodec` Video Decoder + Seamless `AMediaCodec_setOutputSurface`
//! + Zero-Copy `AImageReader` (`AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`) -> `AHardwareBuffer`
//! -> `EGLImageKHR` -> `GL_TEXTURE_EXTERNAL_OES` -> GLES 3.0 AMD FSR 1.0 (EASU + RCAS) renderer.

#![allow(unused_imports, dead_code)]
use crate::ndk_sys::*;
use crate::protocol::video::{
    VIDEO_FORMAT_MASK_H264, VIDEO_FORMAT_MASK_H265,
};
use crate::render::fsr_shaders;
use std::ffi::{c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

static ACTIVE_SURFACE_WINDOW: AtomicUsize = AtomicUsize::new(0);
static ACTIVE_CODEC_PTR: AtomicUsize = AtomicUsize::new(0);
static ACTIVE_READER_WINDOW: AtomicUsize = AtomicUsize::new(0);

static FSR_ENABLED: AtomicBool = AtomicBool::new(false);
static FSR_MODE: AtomicI32 = AtomicI32::new(1); // 0 = none, 1 = rcas, 2 = easu_rcas
static FSR_PRESET: AtomicI32 = AtomicI32::new(1); // 0 = Perf, 1 = Balanced, 2 = Quality
static FSR_SHARPNESS: AtomicI32 = AtomicI32::new(35); // 0..100
static HDR_ACTIVE: AtomicBool = AtomicBool::new(false);
static FRAME_AVAILABLE_COUNT: AtomicU32 = AtomicU32::new(0);
static FRAME_SIGNAL: OnceLock<(Mutex<()>, Condvar)> = OnceLock::new();

fn frame_signal() -> &'static (Mutex<()>, Condvar) {
    FRAME_SIGNAL.get_or_init(|| (Mutex::new(()), Condvar::new()))
}

pub const VIDEO_FORMAT_MASK_AV1: i32 = 0xF000;

/// Called from JNI when the Android `SurfaceView` `Surface` is created, changed, or destroyed.
pub unsafe fn set_native_video_surface(window: *mut ANativeWindow) {
    let prev = ACTIVE_SURFACE_WINDOW.swap(window as usize, Ordering::SeqCst) as *mut ANativeWindow;
    if !prev.is_null() && prev != window {
        #[cfg(target_os = "android")]
        ANativeWindow_release(prev);
    }
    sync_codec_output_surface();
}

/// Updates FSR 1.0 settings at runtime and seamlessly switches `AMediaCodec` output surface
/// between Direct Surface Present (`display_window`) and `AImageReader` (`reader_window`)
/// via `AMediaCodec_setOutputSurface` without recreating the decoder or requesting an IDR frame.
pub fn update_fsr_config(enabled: bool, mode: i32, preset: i32, sharpness: i32, hdr_active: bool) {
    let prev_enabled = FSR_ENABLED.swap(enabled, Ordering::SeqCst);
    FSR_MODE.store(mode.clamp(0, 2), Ordering::Relaxed);
    FSR_PRESET.store(preset.clamp(0, 2), Ordering::Relaxed);
    FSR_SHARPNESS.store(sharpness.clamp(0, 100), Ordering::Relaxed);
    HDR_ACTIVE.store(hdr_active, Ordering::Relaxed);

    if prev_enabled != enabled {
        unsafe {
            sync_codec_output_surface();
        }
    }
}

pub fn has_native_video_surface() -> bool {
    ACTIVE_SURFACE_WINDOW.load(Ordering::Acquire) != 0
}

unsafe fn sync_codec_output_surface() {
    #[cfg(target_os = "android")]
    {
        let codec = ACTIVE_CODEC_PTR.load(Ordering::Acquire) as *mut AMediaCodec;
        if codec.is_null() {
            return;
        }
        let use_fsr = FSR_ENABLED.load(Ordering::Acquire) && FSR_MODE.load(Ordering::Relaxed) != 0;
        let reader_win = ACTIVE_READER_WINDOW.load(Ordering::Acquire) as *mut ANativeWindow;
        let display_win = ACTIVE_SURFACE_WINDOW.load(Ordering::Acquire) as *mut ANativeWindow;

        let target_win = if use_fsr && !reader_win.is_null() {
            reader_win
        } else {
            display_win
        };

        if !target_win.is_null() {
            let _ = AMediaCodec_setOutputSurface(codec, target_win);
        }
    }
}

#[cfg(target_os = "android")]
unsafe extern "C" fn on_image_available_cb(_context: *mut c_void, _reader: *mut AImageReader) {
    FRAME_AVAILABLE_COUNT.fetch_add(1, Ordering::Release);
    let (lock, cvar) = frame_signal();
    let _guard = lock.lock();
    cvar.notify_all();
}

pub struct NdkVideoDecoder {
    video_format: i32,
    width: i32,
    height: i32,
    fps: i32,
    stop_flag: Arc<AtomicBool>,
    #[cfg(target_os = "android")]
    codec: *mut AMediaCodec,
    #[cfg(target_os = "android")]
    image_reader: *mut AImageReader,
    #[cfg(target_os = "android")]
    listener_box: *mut AImageReader_ImageListener,
    output_thread: Option<JoinHandle<()>>,
    gl_thread: Option<JoinHandle<()>>,
    codec_lock: Arc<Mutex<()>>,
}

unsafe impl Send for NdkVideoDecoder {}

impl NdkVideoDecoder {
    pub fn new(video_format: i32, width: i32, height: i32, fps: i32) -> Result<Self, i32> {
        let stop_flag = Arc::new(AtomicBool::new(false));
        let codec_lock = Arc::new(Mutex::new(()));

        #[cfg(target_os = "android")]
        unsafe {
            let display_win = ACTIVE_SURFACE_WINDOW.load(Ordering::Acquire) as *mut ANativeWindow;
            if display_win.is_null() {
                return Err(-1);
            }

            // 1. Create 4-slot AImageReader with AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE for zero-copy FSR 1.0
            let mut image_reader: *mut AImageReader = std::ptr::null_mut();
            let mut reader_window: *mut ANativeWindow = std::ptr::null_mut();
            let mut listener_box: *mut AImageReader_ImageListener = std::ptr::null_mut();

            if AImageReader_newWithUsage(
                width,
                height,
                AIMAGE_FORMAT_PRIVATE,
                AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
                4,
                &mut image_reader,
            ) == AMEDIA_OK
                && !image_reader.is_null()
            {
                let listener = Box::new(AImageReader_ImageListener {
                    context: std::ptr::null_mut(),
                    onImageAvailable: Some(on_image_available_cb),
                });
                listener_box = Box::into_raw(listener);
                let _ = AImageReader_setImageListener(image_reader, listener_box);
                let _ = AImageReader_getWindow(image_reader, &mut reader_window);
                ACTIVE_READER_WINDOW.store(reader_window as usize, Ordering::Release);
            }

            // 2. Create and configure NDK AMediaCodec
            let mime_str = if (video_format & VIDEO_FORMAT_MASK_H265) != 0 {
                "video/hevc"
            } else if (video_format & VIDEO_FORMAT_MASK_AV1) != 0 {
                "video/av01"
            } else {
                "video/avc"
            };
            let c_mime = CString::new(mime_str).unwrap();
            let codec = AMediaCodec_createDecoderByType(c_mime.as_ptr());
            if codec.is_null() {
                if !image_reader.is_null() {
                    AImageReader_delete(image_reader);
                }
                if !listener_box.is_null() {
                    let _ = Box::from_raw(listener_box);
                }
                return Err(-2);
            }

            let fmt = AMediaFormat_new();
            let k_mime = CString::new("mime").unwrap();
            let k_w = CString::new("width").unwrap();
            let k_h = CString::new("height").unwrap();
            let k_ll = CString::new("low-latency").unwrap();
            let k_prio = CString::new("priority").unwrap();
            let k_oprate = CString::new("operating-rate").unwrap();
            let k_qti = CString::new("vendor.qti-ext-dec-low-latency.enable").unwrap();
            let k_hisi = CString::new("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req").unwrap();
            let k_rtc = CString::new("vendor.rtc-ext-dec-low-latency.enable").unwrap();

            AMediaFormat_setString(fmt, k_mime.as_ptr(), c_mime.as_ptr());
            AMediaFormat_setInt32(fmt, k_w.as_ptr(), width);
            AMediaFormat_setInt32(fmt, k_h.as_ptr(), height);
            AMediaFormat_setInt32(fmt, k_ll.as_ptr(), 1);
            AMediaFormat_setInt32(fmt, k_prio.as_ptr(), 0);
            AMediaFormat_setInt32(fmt, k_oprate.as_ptr(), fps.max(120));
            AMediaFormat_setInt32(fmt, k_qti.as_ptr(), 1);
            AMediaFormat_setInt32(fmt, k_hisi.as_ptr(), 1);
            AMediaFormat_setInt32(fmt, k_rtc.as_ptr(), 1);

            let use_fsr = FSR_ENABLED.load(Ordering::Acquire) && FSR_MODE.load(Ordering::Relaxed) != 0;
            let initial_surface = if use_fsr && !reader_window.is_null() {
                reader_window
            } else {
                display_win
            };

            if AMediaCodec_configure(codec, fmt, initial_surface, std::ptr::null_mut(), 0) != AMEDIA_OK
                || AMediaCodec_start(codec) != AMEDIA_OK
            {
                AMediaFormat_delete(fmt);
                AMediaCodec_delete(codec);
                if !image_reader.is_null() {
                    AImageReader_delete(image_reader);
                }
                if !listener_box.is_null() {
                    let _ = Box::from_raw(listener_box);
                }
                return Err(-3);
            }
            AMediaFormat_delete(fmt);
            ACTIVE_CODEC_PTR.store(codec as usize, Ordering::Release);

            // 3. Spawn Output Drain Thread (latest-frame low-latency rendering)
            let stop_out = Arc::clone(&stop_flag);
            let codec_addr = codec as usize;
            let out_handle = thread::Builder::new()
                .name("RustNdkVidOut".into())
                .spawn(move || {
                    crate::cpu_affinity::pin_current_thread_to_big_cores();
                    let codec_ptr = codec_addr as *mut AMediaCodec;
                    let mut info = AMediaCodecBufferInfo::default();
                    while !stop_out.load(Ordering::Relaxed) {
                        let out_idx = AMediaCodec_dequeueOutputBuffer(codec_ptr, &mut info, 4000);
                        if out_idx >= 0 {
                            // Sync LFR: if another frame is already decoded right behind this one,
                            // drop the stale frame and render only the latest one.
                            let mut latest_idx = out_idx;
                            loop {
                                let next_idx = AMediaCodec_dequeueOutputBuffer(codec_ptr, &mut info, 0);
                                if next_idx >= 0 {
                                    let _ = AMediaCodec_releaseOutputBuffer(codec_ptr, latest_idx as usize, false);
                                    latest_idx = next_idx;
                                } else {
                                    break;
                                }
                            }
                            let _ = AMediaCodec_releaseOutputBuffer(codec_ptr, latest_idx as usize, true);
                        }
                    }
                })
                .ok();

            // 4. Spawn Zero-Copy GLES3 FSR 1.0 Render Thread
            let stop_gl = Arc::clone(&stop_flag);
            let reader_addr = image_reader as usize;
            let gl_handle = if !image_reader.is_null() {
                thread::Builder::new()
                    .name("RustGlFsr1".into())
                    .spawn(move || {
                        crate::cpu_affinity::pin_current_thread_to_big_cores();
                        Self::run_zero_copy_fsr_loop(reader_addr as *mut AImageReader, width, height, stop_gl);
                    })
                    .ok()
            } else {
                None
            };

            Ok(Self {
                video_format,
                width,
                height,
                fps,
                stop_flag,
                codec,
                image_reader,
                listener_box,
                output_thread: out_handle,
                gl_thread: gl_handle,
                codec_lock,
            })
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = (video_format, width, height, fps);
            Ok(Self {
                video_format,
                width,
                height,
                fps,
                stop_flag,
                output_thread: None,
                gl_thread: None,
                codec_lock,
            })
        }
    }

    pub fn submit_decode_unit(
        &self,
        data: &[u8],
        decode_unit_type: i32,
        pts_us: u64,
    ) -> i32 {
        #[cfg(target_os = "android")]
        unsafe {
            let _guard = self.codec_lock.lock().unwrap();
            if self.codec.is_null() {
                return -1;
            }
            let in_idx = AMediaCodec_dequeueInputBuffer(self.codec, 5000);
            if in_idx < 0 {
                return -1; // DR_NEED_IDR if input queue is backed up
            }
            let mut buf_size = 0usize;
            let buf_ptr = AMediaCodec_getInputBuffer(self.codec, in_idx as usize, &mut buf_size);
            if buf_ptr.is_null() || buf_size < data.len() {
                let _ = AMediaCodec_queueInputBuffer(self.codec, in_idx as usize, 0, 0, pts_us, 0);
                return -1;
            }
            std::ptr::copy_nonoverlapping(data.as_ptr(), buf_ptr, data.len());
            let flags = if decode_unit_type != crate::protocol::video::BUFFER_TYPE_PICDATA {
                AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
            } else {
                0
            };
            let rc = AMediaCodec_queueInputBuffer(
                self.codec,
                in_idx as usize,
                0,
                data.len(),
                pts_us,
                flags,
            );
            if rc == AMEDIA_OK {
                0
            } else {
                -1
            }
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = (data, decode_unit_type, pts_us);
            0
        }
    }

    #[cfg(target_os = "android")]
    unsafe fn compile_gl_shader(shader_type: GLenum, src: &str) -> GLuint {
        let sh = glCreateShader(shader_type);
        let ptr = src.as_ptr() as *const std::ffi::c_char;
        let len = src.len() as GLint;
        glShaderSource(sh, 1, &ptr, &len);
        glCompileShader(sh);
        let mut ok = 0;
        glGetShaderiv(sh, GL_COMPILE_STATUS, &mut ok);
        if ok == 0 {
            glDeleteShader(sh);
            return 0;
        }
        sh
    }

    #[cfg(target_os = "android")]
    unsafe fn link_gl_program(vs_src: &str, fs_src: &str) -> GLuint {
        let vs = Self::compile_gl_shader(GL_VERTEX_SHADER, vs_src);
        let fs = Self::compile_gl_shader(GL_FRAGMENT_SHADER, fs_src);
        if vs == 0 || fs == 0 {
            if vs != 0 {
                glDeleteShader(vs);
            }
            if fs != 0 {
                glDeleteShader(fs);
            }
            return 0;
        }
        let prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        let a_pos = CString::new("aPos").unwrap();
        let a_uv = CString::new("aUv").unwrap();
        glBindAttribLocation(prog, 0, a_pos.as_ptr());
        glBindAttribLocation(prog, 1, a_uv.as_ptr());
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        let mut ok = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &mut ok);
        if ok == 0 {
            glDeleteProgram(prog);
            return 0;
        }
        prog
    }

    /// Strict zero-copy `AImageReader` (`AImageReader_acquireLatestImage` + guaranteed `ScopedAImage` `AImage_delete`)
    /// -> `AHardwareBuffer` -> `EGLImageKHR` -> `GL_TEXTURE_EXTERNAL_OES` -> GLES3 EASU/RCAS render loop.
    #[cfg(target_os = "android")]
    unsafe fn run_zero_copy_fsr_loop(
        reader: *mut AImageReader,
        src_w: i32,
        src_h: i32,
        stop_flag: Arc<AtomicBool>,
    ) {
        let p_get_client_buf: Option<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC> = {
            let name = CString::new("eglGetNativeClientBufferANDROID").unwrap();
            let ptr = eglGetProcAddress(name.as_ptr());
            if ptr.is_null() {
                None
            } else {
                Some(std::mem::transmute(ptr))
            }
        };
        let p_create_image: Option<PFNEGLCREATEIMAGEKHRPROC> = {
            let name = CString::new("eglCreateImageKHR").unwrap();
            let ptr = eglGetProcAddress(name.as_ptr());
            if ptr.is_null() {
                None
            } else {
                Some(std::mem::transmute(ptr))
            }
        };
        let p_destroy_image: Option<PFNEGLDESTROYIMAGEKHRPROC> = {
            let name = CString::new("eglDestroyImageKHR").unwrap();
            let ptr = eglGetProcAddress(name.as_ptr());
            if ptr.is_null() {
                None
            } else {
                Some(std::mem::transmute(ptr))
            }
        };
        let p_image_target_tex: Option<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC> = {
            let name = CString::new("glEGLImageTargetTexture2DOES").unwrap();
            let ptr = eglGetProcAddress(name.as_ptr());
            if ptr.is_null() {
                None
            } else {
                Some(std::mem::transmute(ptr))
            }
        };

        let (get_client_buf, create_image, destroy_image, image_target_tex) = match (
            p_get_client_buf,
            p_create_image,
            p_destroy_image,
            p_image_target_tex,
        ) {
            (Some(a), Some(b), Some(c), Some(d)) => (a, b, c, d),
            _ => return,
        };

        let mut last_seen_frame = 0u32;
        let mut egl_dpy = EGL_NO_DISPLAY;
        let mut egl_ctx = EGL_NO_CONTEXT;
        let mut egl_surf = EGL_NO_SURFACE;
        let mut egl_cfg: EGLConfig = std::ptr::null_mut();
        let mut bound_window: *mut ANativeWindow = std::ptr::null_mut();

        let mut oes_tex: GLuint = 0;
        let mut prog_rcas_oes: GLuint = 0;
        let mut prog_easu_bal: GLuint = 0;
        let mut vbo_pos: GLuint = 0;
        let mut vbo_uv: GLuint = 0;

        let identity_mat: [f32; 16] = [
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        ];
        // Flip Y for ANativeWindow hardware buffer coordinates
        let quad_pos: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0];
        let quad_uv: [f32; 8] = [0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0];

        let (lock, cvar) = frame_signal();
        while !stop_flag.load(Ordering::Relaxed) {
            let cur_count = FRAME_AVAILABLE_COUNT.load(Ordering::Acquire);
            if cur_count == last_seen_frame {
                if let Ok(guard) = lock.lock() {
                    if FRAME_AVAILABLE_COUNT.load(Ordering::Acquire) == last_seen_frame
                        && !stop_flag.load(Ordering::Relaxed)
                    {
                        let _ = cvar.wait_timeout(guard, Duration::from_millis(50));
                    }
                }
                continue;
            }
            last_seen_frame = cur_count;

            // Always drain latest image and wrap in ScopedAImage so AImage_delete is guaranteed!
            let mut raw_image: *mut AImage = std::ptr::null_mut();
            if AImageReader_acquireLatestImage(reader, &mut raw_image) != AMEDIA_OK || raw_image.is_null() {
                continue;
            }
            let _scoped_image = ScopedAImage(raw_image);

            if !FSR_ENABLED.load(Ordering::Acquire) || FSR_MODE.load(Ordering::Relaxed) == 0 {
                continue;
            }

            let cur_win = ACTIVE_SURFACE_WINDOW.load(Ordering::Acquire) as *mut ANativeWindow;
            if cur_win.is_null() {
                continue;
            }

            // Initialize or rebind EGL window surface if needed
            if egl_dpy == EGL_NO_DISPLAY {
                egl_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
                if egl_dpy == EGL_NO_DISPLAY {
                    continue;
                }
                let mut maj = 0;
                let mut min = 0;
                eglInitialize(egl_dpy, &mut maj, &mut min);
                let cfg_attribs = [
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                    EGL_RED_SIZE, 8,
                    EGL_GREEN_SIZE, 8,
                    EGL_BLUE_SIZE, 8,
                    EGL_ALPHA_SIZE, 8,
                    EGL_NONE,
                ];
                let mut num_cfg = 0;
                eglChooseConfig(egl_dpy, cfg_attribs.as_ptr(), &mut egl_cfg, 1, &mut num_cfg);
                let ctx_attribs = [EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE];
                egl_ctx = eglCreateContext(egl_dpy, egl_cfg, EGL_NO_CONTEXT, ctx_attribs.as_ptr());
            }

            if bound_window != cur_win {
                if egl_surf != EGL_NO_SURFACE {
                    eglMakeCurrent(egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                    eglDestroySurface(egl_dpy, egl_surf);
                }
                let surf_attribs = [EGL_NONE];
                egl_surf = eglCreateWindowSurface(egl_dpy, egl_cfg, cur_win, surf_attribs.as_ptr());
                bound_window = cur_win;
                eglMakeCurrent(egl_dpy, egl_surf, egl_surf, egl_ctx);
                eglSwapInterval(egl_dpy, 0);

                if oes_tex == 0 {
                    glGenTextures(1, &mut oes_tex);
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes_tex);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                    let rcas_oes_fs = format!(
                        "#version 300 es\n#define USE_OES 1\n#define RCAS_OES_VS 1\n{}",
                        fsr_shaders::FS_RCAS.replacen("#version 300 es\n", "", 1)
                    );
                    prog_rcas_oes = Self::link_gl_program(fsr_shaders::VS_RCAS_OES, &rcas_oes_fs);
                    prog_easu_bal = Self::link_gl_program(fsr_shaders::VS, fsr_shaders::FS_EASU_BALANCED);

                    glGenBuffers(1, &mut vbo_pos);
                    glBindBuffer(GL_ARRAY_BUFFER, vbo_pos);
                    glBufferData(
                        GL_ARRAY_BUFFER,
                        (quad_pos.len() * 4) as isize,
                        quad_pos.as_ptr() as *const c_void,
                        GL_STATIC_DRAW,
                    );
                    glGenBuffers(1, &mut vbo_uv);
                    glBindBuffer(GL_ARRAY_BUFFER, vbo_uv);
                    glBufferData(
                        GL_ARRAY_BUFFER,
                        (quad_uv.len() * 4) as isize,
                        quad_uv.as_ptr() as *const c_void,
                        GL_STATIC_DRAW,
                    );
                }
            }

            let mut hw_buf: *mut AHardwareBuffer = std::ptr::null_mut();
            if AImage_getHardwareBuffer(raw_image, &mut hw_buf) != AMEDIA_OK || hw_buf.is_null() {
                continue;
            }
            let client_buf = get_client_buf(hw_buf);
            if client_buf.is_null() {
                continue;
            }
            let img_attribs = [EGL_IMAGE_PRESERVED_KHR, EGL_TRUE as i32, EGL_NONE];
            let egl_img = create_image(
                egl_dpy,
                EGL_NO_CONTEXT,
                EGL_NATIVE_BUFFER_ANDROID,
                client_buf,
                img_attribs.as_ptr(),
            );
            if egl_img == EGL_NO_IMAGE_KHR {
                continue;
            }

            let dst_w = ANativeWindow_getWidth(cur_win).max(1);
            let dst_h = ANativeWindow_getHeight(cur_win).max(1);
            glViewport(0, 0, dst_w, dst_h);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes_tex);
            image_target_tex(GL_TEXTURE_EXTERNAL_OES, egl_img);

            let mode = FSR_MODE.load(Ordering::Relaxed);
            let preset = FSR_PRESET.load(Ordering::Relaxed);
            let ui_sharp = (FSR_SHARPNESS.load(Ordering::Relaxed) as f32) / 100.0;
            let internal_sharp = fsr_shaders::map_ui_sharp_to_internal_rcas(ui_sharp, preset, false);

            let prog = if mode == 2 && prog_easu_bal != 0 {
                prog_easu_bal
            } else {
                prog_rcas_oes
            };

            if prog != 0 {
                glUseProgram(prog);
                let u_tex_mat = CString::new("uTexMatrix").unwrap();
                let loc_mat = glGetUniformLocation(prog, u_tex_mat.as_ptr());
                if loc_mat >= 0 {
                    glUniformMatrix4fv(loc_mat, 1, GL_FALSE, identity_mat.as_ptr());
                }
                let u_inv_src = CString::new("uInvSrcSize").unwrap();
                let loc_src = glGetUniformLocation(prog, u_inv_src.as_ptr());
                if loc_src >= 0 {
                    glUniform2f(loc_src, 1.0 / (src_w.max(1) as f32), 1.0 / (src_h.max(1) as f32));
                }
                let u_inv_dst = CString::new("uInvDstSize").unwrap();
                let loc_dst = glGetUniformLocation(prog, u_inv_dst.as_ptr());
                if loc_dst >= 0 {
                    glUniform2f(loc_dst, 1.0 / (dst_w as f32), 1.0 / (dst_h as f32));
                }
                let u_sharp = CString::new("uSharp").unwrap();
                let loc_sharp = glGetUniformLocation(prog, u_sharp.as_ptr());
                if loc_sharp >= 0 {
                    glUniform1f(loc_sharp, internal_sharp);
                }
                let u_luma = CString::new("uLumaCoeffs").unwrap();
                let loc_luma = glGetUniformLocation(prog, u_luma.as_ptr());
                if loc_luma >= 0 {
                    let luma = if HDR_ACTIVE.load(Ordering::Relaxed) {
                        fsr_shaders::LUMA_2020
                    } else {
                        fsr_shaders::LUMA_709
                    };
                    glUniform3f(loc_luma, luma[0], luma[1], luma[2]);
                }

                glBindBuffer(GL_ARRAY_BUFFER, vbo_pos);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, std::ptr::null());

                glBindBuffer(GL_ARRAY_BUFFER, vbo_uv);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, std::ptr::null());

                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                let _ = eglSwapBuffers(egl_dpy, egl_surf);
            }

            destroy_image(egl_dpy, egl_img);
            // `_scoped_image` drops here and guarantees `AImage_delete(raw_image)`!
        }

        if egl_dpy != EGL_NO_DISPLAY {
            eglMakeCurrent(egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if egl_surf != EGL_NO_SURFACE {
                eglDestroySurface(egl_dpy, egl_surf);
            }
            if egl_ctx != EGL_NO_CONTEXT {
                eglDestroyContext(egl_dpy, egl_ctx);
            }
            eglTerminate(egl_dpy);
        }
    }
}

impl Drop for NdkVideoDecoder {
    fn drop(&mut self) {
        self.stop_flag.store(true, Ordering::Release);
        {
            let (lock, cvar) = frame_signal();
            let _guard = lock.lock();
            cvar.notify_all();
        }
        ACTIVE_CODEC_PTR.store(0, Ordering::Release);
        ACTIVE_READER_WINDOW.store(0, Ordering::Release);

        if let Some(h) = self.output_thread.take() {
            let _ = h.join();
        }
        if let Some(h) = self.gl_thread.take() {
            let _ = h.join();
        }

        #[cfg(target_os = "android")]
        unsafe {
            if !self.codec.is_null() {
                let _ = AMediaCodec_stop(self.codec);
                let _ = AMediaCodec_delete(self.codec);
                self.codec = std::ptr::null_mut();
            }
            if !self.image_reader.is_null() {
                AImageReader_delete(self.image_reader);
                self.image_reader = std::ptr::null_mut();
            }
            if !self.listener_box.is_null() {
                let _ = Box::from_raw(self.listener_box);
                self.listener_box = std::ptr::null_mut();
            }
        }
    }
}
