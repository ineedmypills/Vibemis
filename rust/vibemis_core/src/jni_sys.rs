#![allow(non_snake_case, non_camel_case_types, dead_code)]

use std::ffi::{c_char, c_void, CStr, CString};

pub type jboolean = u8;
pub type jbyte = i8;
pub type jchar = u16;
pub type jshort = i16;
pub type jint = i32;
pub type jlong = i64;
pub type jfloat = f32;
pub type jdouble = f64;
pub type jsize = jint;

pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jbyteArray = jarray;
pub type jshortArray = jarray;
pub type jintArray = jarray;
pub type jmethodID = *mut c_void;

pub const JNI_FALSE: jboolean = 0;
pub const JNI_TRUE: jboolean = 1;
pub const JNI_OK: jint = 0;
pub const JNI_VERSION_1_6: jint = 0x00010006;
pub const JNI_ABORT: jint = 2;

#[repr(C)]
#[derive(Copy, Clone)]
pub union jvalue {
    pub z: jboolean,
    pub b: jbyte,
    pub c: jchar,
    pub s: jshort,
    pub i: jint,
    pub j: jlong,
    pub f: jfloat,
    pub d: jdouble,
    pub l: jobject,
}

pub type JNIEnv = *const JNINativeInterface_;
pub type JavaVM = *const JNIInvokeInterface_;

#[repr(C)]
pub struct JNIInvokeInterface_ {
    pub reserved0: *mut c_void,
    pub reserved1: *mut c_void,
    pub reserved2: *mut c_void,
    pub DestroyJavaVM: unsafe extern "system" fn(vm: *mut JavaVM) -> jint,
    pub AttachCurrentThread:
        unsafe extern "system" fn(vm: *mut JavaVM, p_env: *mut *mut JNIEnv, thr_args: *mut c_void) -> jint,
    pub DetachCurrentThread: unsafe extern "system" fn(vm: *mut JavaVM) -> jint,
    pub GetEnv: unsafe extern "system" fn(vm: *mut JavaVM, p_env: *mut *mut c_void, version: jint) -> jint,
    pub AttachCurrentThreadAsDaemon:
        unsafe extern "system" fn(vm: *mut JavaVM, p_env: *mut *mut JNIEnv, thr_args: *mut c_void) -> jint,
}

#[repr(C)]
pub struct JNINativeInterface_ {
    pub reserved0: *mut c_void,
    pub reserved1: *mut c_void,
    pub reserved2: *mut c_void,
    pub reserved3: *mut c_void,
    pub GetVersion: unsafe extern "system" fn(env: *mut JNIEnv) -> jint,
    pub DefineClass: *mut c_void,
    pub FindClass: unsafe extern "system" fn(env: *mut JNIEnv, name: *const c_char) -> jclass,
    pub FromReflectedMethod: *mut c_void,
    pub FromReflectedField: *mut c_void,
    pub ToReflectedMethod: *mut c_void,
    pub GetSuperclass: *mut c_void,
    pub IsAssignableFrom: *mut c_void,
    pub ToReflectedField: *mut c_void,
    pub Throw: *mut c_void,
    pub ThrowNew: *mut c_void,
    pub ExceptionOccurred: *mut c_void,
    pub ExceptionDescribe: *mut c_void,
    pub ExceptionClear: unsafe extern "system" fn(env: *mut JNIEnv),
    pub FatalError: *mut c_void,
    pub PushLocalFrame: *mut c_void,
    pub PopLocalFrame: *mut c_void,
    pub NewGlobalRef: unsafe extern "system" fn(env: *mut JNIEnv, lobj: jobject) -> jobject,
    pub DeleteGlobalRef: unsafe extern "system" fn(env: *mut JNIEnv, gref: jobject),
    pub DeleteLocalRef: unsafe extern "system" fn(env: *mut JNIEnv, obj: jobject),
    pub IsSameObject: *mut c_void,
    pub NewLocalRef: *mut c_void,
    pub EnsureLocalCapacity: *mut c_void,
    pub AllocObject: *mut c_void,
    pub NewObject: *mut c_void,
    pub NewObjectV: *mut c_void,
    pub NewObjectA: *mut c_void,
    pub GetObjectClass: *mut c_void,
    pub IsInstanceOf: *mut c_void,
    pub GetMethodID: *mut c_void,
    pub CallObjectMethod: *mut c_void,
    pub CallObjectMethodV: *mut c_void,
    pub CallObjectMethodA: *mut c_void,
    pub CallBooleanMethod: *mut c_void,
    pub CallBooleanMethodV: *mut c_void,
    pub CallBooleanMethodA: *mut c_void,
    pub CallByteMethod: *mut c_void,
    pub CallByteMethodV: *mut c_void,
    pub CallByteMethodA: *mut c_void,
    pub CallCharMethod: *mut c_void,
    pub CallCharMethodV: *mut c_void,
    pub CallCharMethodA: *mut c_void,
    pub CallShortMethod: *mut c_void,
    pub CallShortMethodV: *mut c_void,
    pub CallShortMethodA: *mut c_void,
    pub CallIntMethod: *mut c_void,
    pub CallIntMethodV: *mut c_void,
    pub CallIntMethodA: *mut c_void,
    pub CallLongMethod: *mut c_void,
    pub CallLongMethodV: *mut c_void,
    pub CallLongMethodA: *mut c_void,
    pub CallFloatMethod: *mut c_void,
    pub CallFloatMethodV: *mut c_void,
    pub CallFloatMethodA: *mut c_void,
    pub CallDoubleMethod: *mut c_void,
    pub CallDoubleMethodV: *mut c_void,
    pub CallDoubleMethodA: *mut c_void,
    pub CallVoidMethod: *mut c_void,
    pub CallVoidMethodV: *mut c_void,
    pub CallVoidMethodA: *mut c_void,
    pub CallNonvirtualObjectMethod: *mut c_void,
    pub CallNonvirtualObjectMethodV: *mut c_void,
    pub CallNonvirtualObjectMethodA: *mut c_void,
    pub CallNonvirtualBooleanMethod: *mut c_void,
    pub CallNonvirtualBooleanMethodV: *mut c_void,
    pub CallNonvirtualBooleanMethodA: *mut c_void,
    pub CallNonvirtualByteMethod: *mut c_void,
    pub CallNonvirtualByteMethodV: *mut c_void,
    pub CallNonvirtualByteMethodA: *mut c_void,
    pub CallNonvirtualCharMethod: *mut c_void,
    pub CallNonvirtualCharMethodV: *mut c_void,
    pub CallNonvirtualCharMethodA: *mut c_void,
    pub CallNonvirtualShortMethod: *mut c_void,
    pub CallNonvirtualShortMethodV: *mut c_void,
    pub CallNonvirtualShortMethodA: *mut c_void,
    pub CallNonvirtualIntMethod: *mut c_void,
    pub CallNonvirtualIntMethodV: *mut c_void,
    pub CallNonvirtualIntMethodA: *mut c_void,
    pub CallNonvirtualLongMethod: *mut c_void,
    pub CallNonvirtualLongMethodV: *mut c_void,
    pub CallNonvirtualLongMethodA: *mut c_void,
    pub CallNonvirtualFloatMethod: *mut c_void,
    pub CallNonvirtualFloatMethodV: *mut c_void,
    pub CallNonvirtualFloatMethodA: *mut c_void,
    pub CallNonvirtualDoubleMethod: *mut c_void,
    pub CallNonvirtualDoubleMethodV: *mut c_void,
    pub CallNonvirtualDoubleMethodA: *mut c_void,
    pub CallNonvirtualVoidMethod: *mut c_void,
    pub CallNonvirtualVoidMethodV: *mut c_void,
    pub CallNonvirtualVoidMethodA: *mut c_void,
    pub GetFieldID: *mut c_void,
    pub GetObjectField: *mut c_void,
    pub GetBooleanField: *mut c_void,
    pub GetByteField: *mut c_void,
    pub GetCharField: *mut c_void,
    pub GetShortField: *mut c_void,
    pub GetIntField: *mut c_void,
    pub GetLongField: *mut c_void,
    pub GetFloatField: *mut c_void,
    pub GetDoubleField: *mut c_void,
    pub SetObjectField: *mut c_void,
    pub SetBooleanField: *mut c_void,
    pub SetByteField: *mut c_void,
    pub SetCharField: *mut c_void,
    pub SetShortField: *mut c_void,
    pub SetIntField: *mut c_void,
    pub SetLongField: *mut c_void,
    pub SetFloatField: *mut c_void,
    pub SetDoubleField: *mut c_void,
    pub GetStaticMethodID: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        name: *const c_char,
        sig: *const c_char,
    ) -> jmethodID,
    pub CallStaticObjectMethod: *mut c_void,
    pub CallStaticObjectMethodV: *mut c_void,
    pub CallStaticObjectMethodA: *mut c_void,
    pub CallStaticBooleanMethod: *mut c_void,
    pub CallStaticBooleanMethodV: *mut c_void,
    pub CallStaticBooleanMethodA: *mut c_void,
    pub CallStaticByteMethod: *mut c_void,
    pub CallStaticByteMethodV: *mut c_void,
    pub CallStaticByteMethodA: *mut c_void,
    pub CallStaticCharMethod: *mut c_void,
    pub CallStaticCharMethodV: *mut c_void,
    pub CallStaticCharMethodA: *mut c_void,
    pub CallStaticShortMethod: *mut c_void,
    pub CallStaticShortMethodV: *mut c_void,
    pub CallStaticShortMethodA: *mut c_void,
    pub CallStaticIntMethod: *mut c_void,
    pub CallStaticIntMethodV: *mut c_void,
    pub CallStaticIntMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ) -> jint,
    pub CallStaticLongMethod: *mut c_void,
    pub CallStaticLongMethodV: *mut c_void,
    pub CallStaticLongMethodA: *mut c_void,
    pub CallStaticFloatMethod: *mut c_void,
    pub CallStaticFloatMethodV: *mut c_void,
    pub CallStaticFloatMethodA: *mut c_void,
    pub CallStaticDoubleMethod: *mut c_void,
    pub CallStaticDoubleMethodV: *mut c_void,
    pub CallStaticDoubleMethodA: *mut c_void,
    pub CallStaticVoidMethod: *mut c_void,
    pub CallStaticVoidMethodV: *mut c_void,
    pub CallStaticVoidMethodA: unsafe extern "system" fn(
        env: *mut JNIEnv,
        clazz: jclass,
        methodID: jmethodID,
        args: *const jvalue,
    ),
    pub GetStaticFieldID: *mut c_void,
    pub GetStaticObjectField: *mut c_void,
    pub GetStaticBooleanField: *mut c_void,
    pub GetStaticByteField: *mut c_void,
    pub GetStaticCharField: *mut c_void,
    pub GetStaticShortField: *mut c_void,
    pub GetStaticIntField: *mut c_void,
    pub GetStaticLongField: *mut c_void,
    pub GetStaticFloatField: *mut c_void,
    pub GetStaticDoubleField: *mut c_void,
    pub SetStaticObjectField: *mut c_void,
    pub SetStaticBooleanField: *mut c_void,
    pub SetStaticByteField: *mut c_void,
    pub SetStaticCharField: *mut c_void,
    pub SetStaticShortField: *mut c_void,
    pub SetStaticIntField: *mut c_void,
    pub SetStaticLongField: *mut c_void,
    pub SetStaticFloatField: *mut c_void,
    pub SetStaticDoubleField: *mut c_void,
    pub NewString: *mut c_void,
    pub GetStringLength: *mut c_void,
    pub GetStringChars: *mut c_void,
    pub ReleaseStringChars: *mut c_void,
    pub NewStringUTF: unsafe extern "system" fn(env: *mut JNIEnv, utf: *const c_char) -> jstring,
    pub GetStringUTFLength: unsafe extern "system" fn(env: *mut JNIEnv, str: jstring) -> jsize,
    pub GetStringUTFChars:
        unsafe extern "system" fn(env: *mut JNIEnv, str: jstring, isCopy: *mut jboolean) -> *const c_char,
    pub ReleaseStringUTFChars:
        unsafe extern "system" fn(env: *mut JNIEnv, str: jstring, chars: *const c_char),
    pub GetArrayLength: unsafe extern "system" fn(env: *mut JNIEnv, array: jarray) -> jsize,
    pub NewObjectArray: *mut c_void,
    pub GetObjectArrayElement: *mut c_void,
    pub SetObjectArrayElement: *mut c_void,
    pub NewBooleanArray: *mut c_void,
    pub NewByteArray: unsafe extern "system" fn(env: *mut JNIEnv, len: jsize) -> jbyteArray,
    pub NewCharArray: *mut c_void,
    pub NewShortArray: unsafe extern "system" fn(env: *mut JNIEnv, len: jsize) -> jshortArray,
    pub NewIntArray: unsafe extern "system" fn(env: *mut JNIEnv, len: jsize) -> jintArray,
    pub NewLongArray: *mut c_void,
    pub NewFloatArray: *mut c_void,
    pub NewDoubleArray: *mut c_void,
    pub GetBooleanArrayElements: *mut c_void,
    pub GetByteArrayElements: *mut c_void,
    pub GetCharArrayElements: *mut c_void,
    pub GetShortArrayElements: *mut c_void,
    pub GetIntArrayElements: *mut c_void,
    pub GetLongArrayElements: *mut c_void,
    pub GetFloatArrayElements: *mut c_void,
    pub GetDoubleArrayElements: *mut c_void,
    pub ReleaseBooleanArrayElements: *mut c_void,
    pub ReleaseByteArrayElements: *mut c_void,
    pub ReleaseCharArrayElements: *mut c_void,
    pub ReleaseShortArrayElements: *mut c_void,
    pub ReleaseIntArrayElements: *mut c_void,
    pub ReleaseLongArrayElements: *mut c_void,
    pub ReleaseFloatArrayElements: *mut c_void,
    pub ReleaseDoubleArrayElements: *mut c_void,
    pub GetBooleanArrayRegion: *mut c_void,
    pub GetByteArrayRegion:
        unsafe extern "system" fn(env: *mut JNIEnv, array: jbyteArray, start: jsize, l: jsize, buf: *mut jbyte),
    pub GetCharArrayRegion: *mut c_void,
    pub GetShortArrayRegion: *mut c_void,
    pub GetIntArrayRegion:
        unsafe extern "system" fn(env: *mut JNIEnv, array: jintArray, start: jsize, l: jsize, buf: *mut jint),
    pub GetLongArrayRegion: *mut c_void,
    pub GetFloatArrayRegion: *mut c_void,
    pub GetDoubleArrayRegion: *mut c_void,
    pub SetBooleanArrayRegion: *mut c_void,
    pub SetByteArrayRegion:
        unsafe extern "system" fn(env: *mut JNIEnv, array: jbyteArray, start: jsize, l: jsize, buf: *const jbyte),
    pub SetCharArrayRegion: *mut c_void,
    pub SetShortArrayRegion:
        unsafe extern "system" fn(env: *mut JNIEnv, array: jshortArray, start: jsize, l: jsize, buf: *const jshort),
    pub SetIntArrayRegion: *mut c_void,
    pub SetLongArrayRegion: *mut c_void,
    pub SetFloatArrayRegion: *mut c_void,
    pub SetDoubleArrayRegion: *mut c_void,
    pub RegisterNatives: *mut c_void,
    pub UnregisterNatives: *mut c_void,
    pub MonitorEnter: *mut c_void,
    pub MonitorExit: *mut c_void,
    pub GetJavaVM: unsafe extern "system" fn(env: *mut JNIEnv, vm: *mut *mut JavaVM) -> jint,
    pub GetStringRegion: *mut c_void,
    pub GetStringUTFRegion: *mut c_void,
    pub GetPrimitiveArrayCritical: *mut c_void,
    pub ReleasePrimitiveArrayCritical: *mut c_void,
    pub GetStringCritical: *mut c_void,
    pub ReleaseStringCritical: *mut c_void,
    pub NewWeakGlobalRef: *mut c_void,
    pub DeleteWeakGlobalRef: *mut c_void,
    pub ExceptionCheck: unsafe extern "system" fn(env: *mut JNIEnv) -> jboolean,
}

pub unsafe fn jstring_to_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    if env.is_null() || s.is_null() {
        return None;
    }
    let ptr = ((**env).GetStringUTFChars)(env, s, std::ptr::null_mut());
    if ptr.is_null() {
        return None;
    }
    let rust_str = CStr::from_ptr(ptr).to_string_lossy().into_owned();
    ((**env).ReleaseStringUTFChars)(env, s, ptr);
    Some(rust_str)
}

pub unsafe fn string_to_jstring(env: *mut JNIEnv, s: &str) -> jstring {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    match CString::new(s) {
        Ok(cstr) => ((**env).NewStringUTF)(env, cstr.as_ptr()),
        Err(_) => std::ptr::null_mut(),
    }
}

pub unsafe fn jbytearray_to_vec(env: *mut JNIEnv, arr: jbyteArray) -> Vec<u8> {
    if env.is_null() || arr.is_null() {
        return Vec::new();
    }
    let len = ((**env).GetArrayLength)(env, arr);
    if len <= 0 {
        return Vec::new();
    }
    let mut buf = vec![0u8; len as usize];
    ((**env).GetByteArrayRegion)(env, arr, 0, len, buf.as_mut_ptr() as *mut jbyte);
    buf
}

pub unsafe fn slice_to_jbytearray(env: *mut JNIEnv, data: &[u8]) -> jbyteArray {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let jarr = ((**env).NewByteArray)(env, data.len() as jsize);
    if !jarr.is_null() && !data.is_empty() {
        ((**env).SetByteArrayRegion)(env, jarr, 0, data.len() as jsize, data.as_ptr() as *const jbyte);
    }
    jarr
}
