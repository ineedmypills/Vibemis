//! Android NDK API-29 FFI bindings for `libmediandk`, `libnativewindow`, `libaaudio`,
//! `libEGL`, `libGLESv3`, and `libandroid` (`ASensorManager`).

#![allow(non_camel_case_types, non_snake_case, dead_code, unused_imports)]

use crate::jni_sys::{jobject, JNIEnv};
use std::ffi::{c_char, c_int, c_uint, c_void};

#[repr(C)]
pub struct ANativeWindow {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AHardwareBuffer {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AMediaCodec {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AMediaFormat {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AImageReader {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AImage {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AAudioStreamBuilder {
    _private: [u8; 0],
}

#[repr(C)]
pub struct AAudioStream {
    _private: [u8; 0],
}

#[repr(C)]
pub struct ASensorManager {
    _private: [u8; 0],
}

#[repr(C)]
pub struct ASensor {
    _private: [u8; 0],
}

#[repr(C)]
pub struct ASensorEventQueue {
    _private: [u8; 0],
}

#[repr(C)]
pub struct ALooper {
    _private: [u8; 0],
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct AMediaCodecBufferInfo {
    pub offset: i32,
    pub size: i32,
    pub presentationTimeUs: i64,
    pub flags: u32,
}

#[repr(C)]
pub struct AImageReader_ImageListener {
    pub context: *mut c_void,
    pub onImageAvailable: Option<unsafe extern "C" fn(context: *mut c_void, reader: *mut AImageReader)>,
}

pub type media_status_t = i32;
pub type aaudio_result_t = i32;
pub type aaudio_data_callback_result_t = i32;
pub type aaudio_direction_t = i32;
pub type aaudio_format_t = i32;
pub type aaudio_performance_mode_t = i32;
pub type aaudio_sharing_mode_t = i32;
pub type aaudio_usage_t = i32;

pub type AAudioStream_dataCallback = unsafe extern "C" fn(
    stream: *mut AAudioStream,
    userData: *mut c_void,
    audioData: *mut c_void,
    numFrames: i32,
) -> aaudio_data_callback_result_t;

pub type AAudioStream_errorCallback = unsafe extern "C" fn(
    stream: *mut AAudioStream,
    userData: *mut c_void,
    error: aaudio_result_t,
);

// Media status constants
pub const AMEDIA_OK: media_status_t = 0;
pub const AMEDIACODEC_INFO_TRY_AGAIN_LATER: isize = -1;
pub const AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED: isize = -2;
pub const AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED: isize = -3;
pub const AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG: u32 = 2;
pub const AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM: u32 = 4;

// AImageReader & AHardwareBuffer constants
pub const AIMAGE_FORMAT_PRIVATE: i32 = 0x22;
pub const AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE: u64 = 1 << 8; // 0x100

// AAudio constants
pub const AAUDIO_OK: aaudio_result_t = 0;
pub const AAUDIO_ERROR_DISCONNECTED: aaudio_result_t = -899;
pub const AAUDIO_DIRECTION_OUTPUT: aaudio_direction_t = 0;
pub const AAUDIO_FORMAT_PCM_I16: aaudio_format_t = 1;
pub const AAUDIO_FORMAT_PCM_FLOAT: aaudio_format_t = 2;
pub const AAUDIO_SHARING_MODE_EXCLUSIVE: aaudio_sharing_mode_t = 0;
pub const AAUDIO_SHARING_MODE_SHARED: aaudio_sharing_mode_t = 1;
pub const AAUDIO_PERFORMANCE_MODE_NONE: aaudio_performance_mode_t = 10;
pub const AAUDIO_PERFORMANCE_MODE_POWER_SAVING: aaudio_performance_mode_t = 11;
pub const AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: aaudio_performance_mode_t = 12;
pub const AAUDIO_USAGE_MEDIA: aaudio_usage_t = 1;
pub const AAUDIO_USAGE_GAME: aaudio_usage_t = 14;
pub const AAUDIO_CALLBACK_RESULT_CONTINUE: aaudio_data_callback_result_t = 0;
pub const AAUDIO_CALLBACK_RESULT_STOP: aaudio_data_callback_result_t = 1;

// ASensor constants
pub const ASENSOR_TYPE_ACCELEROMETER: c_int = 1;
pub const ASENSOR_TYPE_GYROSCOPE: c_int = 4;
pub const ALOOPER_PREPARE_ALLOW_NON_CALLBACKS: c_int = 1;

// EGL & GLESv3 types and constants
pub type EGLDisplay = *mut c_void;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLClientBuffer = *mut c_void;
pub type EGLImageKHR = *mut c_void;
pub type EGLint = i32;
pub type EGLBoolean = u32;
pub type EGLenum = u32;
pub type GLuint = u32;
pub type GLint = i32;
pub type GLenum = u32;
pub type GLsizei = i32;
pub type GLboolean = u8;

pub const EGL_DEFAULT_DISPLAY: *mut c_void = std::ptr::null_mut();
pub const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
pub const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
pub const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
pub const EGL_NO_IMAGE_KHR: EGLImageKHR = std::ptr::null_mut();
pub const EGL_FALSE: EGLBoolean = 0;
pub const EGL_TRUE: EGLBoolean = 1;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_OPENGL_ES2_BIT: EGLint = 0x0004;
pub const EGL_OPENGL_ES3_BIT_KHR: EGLint = 0x0040;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
pub const EGL_IMAGE_PRESERVED_KHR: EGLint = 0x30D2;
pub const EGL_NATIVE_BUFFER_ANDROID: EGLenum = 0x3140;

pub const GL_FALSE: GLboolean = 0;
pub const GL_TRUE: GLboolean = 1;
pub const GL_COLOR_BUFFER_BIT: GLenum = 0x00004000;
pub const GL_TRIANGLES: GLenum = 0x0004;
pub const GL_TRIANGLE_STRIP: GLenum = 0x0005;
pub const GL_FLOAT: GLenum = 0x1406;
pub const GL_UNSIGNED_BYTE: GLenum = 0x1401;
pub const GL_RGBA: GLenum = 0x1908;
pub const GL_RGBA8: GLenum = 0x8058;
pub const GL_TEXTURE_2D: GLenum = 0x0DE1;
pub const GL_TEXTURE_EXTERNAL_OES: GLenum = 0x8D65;
pub const GL_TEXTURE0: GLenum = 0x84C0;
pub const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
pub const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
pub const GL_NEAREST: GLint = 0x2600;
pub const GL_LINEAR: GLint = 0x2601;
pub const GL_CLAMP_TO_EDGE: GLint = 0x812F;
pub const GL_FRAMEBUFFER: GLenum = 0x8D40;
pub const GL_COLOR_ATTACHMENT0: GLenum = 0x8CE0;
pub const GL_FRAMEBUFFER_COMPLETE: GLenum = 0x8CD5;
pub const GL_ARRAY_BUFFER: GLenum = 0x8892;
pub const GL_STATIC_DRAW: GLenum = 0x88E4;
pub const GL_FRAGMENT_SHADER: GLenum = 0x8B30;
pub const GL_VERTEX_SHADER: GLenum = 0x8B31;
pub const GL_COMPILE_STATUS: GLenum = 0x8B81;
pub const GL_LINK_STATUS: GLenum = 0x8B82;

pub type PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC =
    unsafe extern "C" fn(buffer: *const AHardwareBuffer) -> EGLClientBuffer;
pub type PFNEGLCREATEIMAGEKHRPROC = unsafe extern "C" fn(
    dpy: EGLDisplay,
    ctx: EGLContext,
    target: EGLenum,
    buffer: EGLClientBuffer,
    attrib_list: *const EGLint,
) -> EGLImageKHR;
pub type PFNEGLDESTROYIMAGEKHRPROC =
    unsafe extern "C" fn(dpy: EGLDisplay, image: EGLImageKHR) -> EGLBoolean;
pub type PFNGLEGLIMAGETARGETTEXTURE2DOESPROC =
    unsafe extern "C" fn(target: GLenum, image: EGLImageKHR);

#[cfg(target_os = "android")]
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut JNIEnv, surface: jobject) -> *mut ANativeWindow;
    pub fn ANativeWindow_acquire(window: *mut ANativeWindow);
    pub fn ANativeWindow_release(window: *mut ANativeWindow);
    pub fn ANativeWindow_getWidth(window: *mut ANativeWindow) -> i32;
    pub fn ANativeWindow_getHeight(window: *mut ANativeWindow) -> i32;
    pub fn ANativeWindow_setBuffersGeometry(
        window: *mut ANativeWindow,
        width: i32,
        height: i32,
        format: i32,
    ) -> i32;

    pub fn ALooper_prepare(opts: c_int) -> *mut ALooper;
    pub fn ALooper_forThread() -> *mut ALooper;
    pub fn ALooper_pollOnce(
        timeoutMillis: c_int,
        outFd: *mut c_int,
        outEvents: *mut c_int,
        outData: *mut *mut c_void,
    ) -> c_int;
    pub fn ASensorManager_getInstanceForPackage(packageName: *const c_char) -> *mut ASensorManager;
    pub fn ASensorManager_getDefaultSensor(
        manager: *mut ASensorManager,
        sensor_type: c_int,
    ) -> *const ASensor;
    pub fn ASensorManager_createEventQueue(
        manager: *mut ASensorManager,
        looper: *mut ALooper,
        ident: c_int,
        callback: Option<unsafe extern "C" fn(fd: c_int, events: c_int, data: *mut c_void) -> c_int>,
        data: *mut c_void,
    ) -> *mut ASensorEventQueue;
    pub fn ASensorManager_destroyEventQueue(
        manager: *mut ASensorManager,
        queue: *mut ASensorEventQueue,
    ) -> c_int;
    pub fn ASensorEventQueue_enableSensor(
        queue: *mut ASensorEventQueue,
        sensor: *const ASensor,
    ) -> c_int;
    pub fn ASensorEventQueue_disableSensor(
        queue: *mut ASensorEventQueue,
        sensor: *const ASensor,
    ) -> c_int;
    pub fn ASensorEventQueue_setEventRate(
        queue: *mut ASensorEventQueue,
        sensor: *const ASensor,
        usec: i32,
    ) -> c_int;
    pub fn ASensorEventQueue_getEvents(
        queue: *mut ASensorEventQueue,
        events: *mut c_void,
        count: usize,
    ) -> isize;
}

#[cfg(target_os = "android")]
#[link(name = "mediandk")]
extern "C" {
    pub fn AMediaCodec_createDecoderByType(mime_type: *const c_char) -> *mut AMediaCodec;
    pub fn AMediaCodec_delete(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_configure(
        codec: *mut AMediaCodec,
        format: *const AMediaFormat,
        surface: *mut ANativeWindow,
        crypto: *mut c_void,
        flags: u32,
    ) -> media_status_t;
    pub fn AMediaCodec_start(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_stop(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_flush(codec: *mut AMediaCodec) -> media_status_t;
    pub fn AMediaCodec_setOutputSurface(
        codec: *mut AMediaCodec,
        surface: *mut ANativeWindow,
    ) -> media_status_t;
    pub fn AMediaCodec_dequeueInputBuffer(
        codec: *mut AMediaCodec,
        timeoutUs: i64,
    ) -> isize;
    pub fn AMediaCodec_getInputBuffer(
        codec: *mut AMediaCodec,
        idx: usize,
        out_size: *mut usize,
    ) -> *mut u8;
    pub fn AMediaCodec_queueInputBuffer(
        codec: *mut AMediaCodec,
        idx: usize,
        offset: usize,
        size: usize,
        time: u64,
        flags: u32,
    ) -> media_status_t;
    pub fn AMediaCodec_dequeueOutputBuffer(
        codec: *mut AMediaCodec,
        info: *mut AMediaCodecBufferInfo,
        timeoutUs: i64,
    ) -> isize;
    pub fn AMediaCodec_getOutputBuffer(
        codec: *mut AMediaCodec,
        idx: usize,
        out_size: *mut usize,
    ) -> *mut u8;
    pub fn AMediaCodec_releaseOutputBuffer(
        codec: *mut AMediaCodec,
        idx: usize,
        render: bool,
    ) -> media_status_t;
    pub fn AMediaCodec_releaseOutputBufferAtTime(
        codec: *mut AMediaCodec,
        idx: usize,
        timestampNs: i64,
    ) -> media_status_t;

    pub fn AMediaFormat_new() -> *mut AMediaFormat;
    pub fn AMediaFormat_delete(format: *mut AMediaFormat) -> media_status_t;
    pub fn AMediaFormat_setString(
        format: *mut AMediaFormat,
        name: *const c_char,
        value: *const c_char,
    );
    pub fn AMediaFormat_setInt32(format: *mut AMediaFormat, name: *const c_char, value: i32);
    pub fn AMediaFormat_setBuffer(
        format: *mut AMediaFormat,
        name: *const c_char,
        data: *const c_void,
        size: usize,
    );

    pub fn AImageReader_newWithUsage(
        width: i32,
        height: i32,
        format: i32,
        usage: u64,
        maxImages: i32,
        reader: *mut *mut AImageReader,
    ) -> media_status_t;
    pub fn AImageReader_delete(reader: *mut AImageReader);
    pub fn AImageReader_getWindow(
        reader: *mut AImageReader,
        window: *mut *mut ANativeWindow,
    ) -> media_status_t;
    pub fn AImageReader_acquireLatestImage(
        reader: *mut AImageReader,
        image: *mut *mut AImage,
    ) -> media_status_t;
    pub fn AImageReader_setImageListener(
        reader: *mut AImageReader,
        listener: *mut AImageReader_ImageListener,
    ) -> media_status_t;
    pub fn AImage_getHardwareBuffer(
        image: *const AImage,
        buffer: *mut *mut AHardwareBuffer,
    ) -> media_status_t;
    pub fn AImage_delete(image: *mut AImage);
}

#[cfg(target_os = "android")]
#[link(name = "aaudio")]
extern "C" {
    pub fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> aaudio_result_t;
    pub fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder) -> aaudio_result_t;
    pub fn AAudioStreamBuilder_setDirection(
        builder: *mut AAudioStreamBuilder,
        direction: aaudio_direction_t,
    );
    pub fn AAudioStreamBuilder_setSampleRate(builder: *mut AAudioStreamBuilder, sampleRate: i32);
    pub fn AAudioStreamBuilder_setChannelCount(
        builder: *mut AAudioStreamBuilder,
        channelCount: i32,
    );
    pub fn AAudioStreamBuilder_setFormat(
        builder: *mut AAudioStreamBuilder,
        format: aaudio_format_t,
    );
    pub fn AAudioStreamBuilder_setSharingMode(
        builder: *mut AAudioStreamBuilder,
        sharingMode: aaudio_sharing_mode_t,
    );
    pub fn AAudioStreamBuilder_setPerformanceMode(
        builder: *mut AAudioStreamBuilder,
        performanceMode: aaudio_performance_mode_t,
    );
    pub fn AAudioStreamBuilder_setUsage(builder: *mut AAudioStreamBuilder, usage: aaudio_usage_t);
    pub fn AAudioStreamBuilder_setDataCallback(
        builder: *mut AAudioStreamBuilder,
        callback: AAudioStream_dataCallback,
        userData: *mut c_void,
    );
    pub fn AAudioStreamBuilder_setErrorCallback(
        builder: *mut AAudioStreamBuilder,
        callback: AAudioStream_errorCallback,
        userData: *mut c_void,
    );
    pub fn AAudioStreamBuilder_openStream(
        builder: *mut AAudioStreamBuilder,
        stream: *mut *mut AAudioStream,
    ) -> aaudio_result_t;
    pub fn AAudioStream_requestStart(stream: *mut AAudioStream) -> aaudio_result_t;
    pub fn AAudioStream_requestStop(stream: *mut AAudioStream) -> aaudio_result_t;
    pub fn AAudioStream_close(stream: *mut AAudioStream) -> aaudio_result_t;
    pub fn AAudioStream_getAudioSessionId(stream: *mut AAudioStream) -> i32;
}

#[cfg(target_os = "android")]
#[link(name = "EGL")]
extern "C" {
    pub fn eglGetDisplay(display_id: *mut c_void) -> EGLDisplay;
    pub fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLBoolean;
    pub fn eglChooseConfig(
        dpy: EGLDisplay,
        attrib_list: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        num_config: *mut EGLint,
    ) -> EGLBoolean;
    pub fn eglCreateContext(
        dpy: EGLDisplay,
        config: EGLConfig,
        share_context: EGLContext,
        attrib_list: *const EGLint,
    ) -> EGLContext;
    pub fn eglCreateWindowSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        win: *mut ANativeWindow,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    pub fn eglMakeCurrent(
        dpy: EGLDisplay,
        draw: EGLSurface,
        read: EGLSurface,
        ctx: EGLContext,
    ) -> EGLBoolean;
    pub fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglSwapInterval(dpy: EGLDisplay, interval: EGLint) -> EGLBoolean;
    pub fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLBoolean;
    pub fn eglTerminate(dpy: EGLDisplay) -> EGLBoolean;
    pub fn eglGetProcAddress(procname: *const c_char) -> *const c_void;
}

#[cfg(target_os = "android")]
#[link(name = "GLESv3")]
extern "C" {
    pub fn glViewport(x: GLint, y: GLint, width: GLsizei, height: GLsizei);
    pub fn glClearColor(red: f32, green: f32, blue: f32, alpha: f32);
    pub fn glClear(mask: GLenum);
    pub fn glCreateShader(shader_type: GLenum) -> GLuint;
    pub fn glShaderSource(
        shader: GLuint,
        count: GLsizei,
        string: *const *const c_char,
        length: *const GLint,
    );
    pub fn glCompileShader(shader: GLuint);
    pub fn glGetShaderiv(shader: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glDeleteShader(shader: GLuint);
    pub fn glCreateProgram() -> GLuint;
    pub fn glAttachShader(program: GLuint, shader: GLuint);
    pub fn glBindAttribLocation(program: GLuint, index: GLuint, name: *const c_char);
    pub fn glLinkProgram(program: GLuint);
    pub fn glGetProgramiv(program: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glUseProgram(program: GLuint);
    pub fn glDeleteProgram(program: GLuint);
    pub fn glGenTextures(n: GLsizei, textures: *mut GLuint);
    pub fn glBindTexture(target: GLenum, texture: GLuint);
    pub fn glActiveTexture(texture: GLenum);
    pub fn glTexParameteri(target: GLenum, pname: GLenum, param: GLint);
    pub fn glTexImage2D(
        target: GLenum,
        level: GLint,
        internalformat: GLint,
        width: GLsizei,
        height: GLsizei,
        border: GLint,
        format: GLenum,
        pixel_type: GLenum,
        pixels: *const c_void,
    );
    pub fn glDeleteTextures(n: GLsizei, textures: *const GLuint);
    pub fn glGenFramebuffers(n: GLsizei, framebuffers: *mut GLuint);
    pub fn glBindFramebuffer(target: GLenum, framebuffer: GLuint);
    pub fn glFramebufferTexture2D(
        target: GLenum,
        attachment: GLenum,
        textarget: GLenum,
        texture: GLuint,
        level: GLint,
    );
    pub fn glCheckFramebufferStatus(target: GLenum) -> GLenum;
    pub fn glDeleteFramebuffers(n: GLsizei, framebuffers: *const GLuint);
    pub fn glGetUniformLocation(program: GLuint, name: *const c_char) -> GLint;
    pub fn glUniform1i(location: GLint, v0: GLint);
    pub fn glUniform1f(location: GLint, v0: f32);
    pub fn glUniform2f(location: GLint, v0: f32, v1: f32);
    pub fn glUniform3f(location: GLint, v0: f32, v1: f32, v2: f32);
    pub fn glUniformMatrix4fv(
        location: GLint,
        count: GLsizei,
        transpose: GLboolean,
        value: *const f32,
    );
    pub fn glGenBuffers(n: GLsizei, buffers: *mut GLuint);
    pub fn glBindBuffer(target: GLenum, buffer: GLuint);
    pub fn glBufferData(
        target: GLenum,
        size: isize,
        data: *const c_void,
        usage: GLenum,
    );
    pub fn glDeleteBuffers(n: GLsizei, buffers: *const GLuint);
    pub fn glEnableVertexAttribArray(index: GLuint);
    pub fn glVertexAttribPointer(
        index: GLuint,
        size: GLint,
        data_type: GLenum,
        normalized: GLboolean,
        stride: GLsizei,
        pointer: *const c_void,
    );
    pub fn glDrawArrays(mode: GLenum, first: GLint, count: GLsizei);
}

/// RAII guard that guarantees `AImage_delete` is called on every acquired `AImage`
/// so the 4-slot `AImageReader` queue can never stall `AMediaCodec`.
pub struct ScopedAImage(pub *mut AImage);

impl Drop for ScopedAImage {
    fn drop(&mut self) {
        if !self.0.is_null() {
            #[cfg(target_os = "android")]
            unsafe {
                AImage_delete(self.0);
            }
            self.0 = std::ptr::null_mut();
        }
    }
}
