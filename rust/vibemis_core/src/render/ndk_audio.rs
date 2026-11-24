//! Native Android NDK `AAudio` Low-Latency FastTrack output + NDK `AMediaCodec` Opus decoder
//! backed by a lock-free SPSC ringbuffer and automatic `AAUDIO_ERROR_DISCONNECTED` recovery.

#![allow(unused_imports)]
use crate::ndk_sys::*;
use std::cell::UnsafeCell;
use std::ffi::{c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

/// Lock-free Single-Producer Single-Consumer (SPSC) ringbuffer for 16-bit PCM samples.
/// Capacity must be a power of two.
pub struct SpscAudioRingBuffer {
    buffer: Box<[UnsafeCell<i16>]>,
    mask: usize,
    write_idx: AtomicUsize,
    read_idx: AtomicUsize,
    reconnect_needed: AtomicBool,
    last_frame_plc: UnsafeCell<Vec<i16>>,
}

unsafe impl Send for SpscAudioRingBuffer {}
unsafe impl Sync for SpscAudioRingBuffer {}

impl SpscAudioRingBuffer {
    pub fn new(capacity_power_of_two: usize, frame_samples: usize) -> Self {
        let cap = capacity_power_of_two.next_power_of_two().max(4096);
        let mut vec = Vec::with_capacity(cap);
        for _ in 0..cap {
            vec.push(UnsafeCell::new(0i16));
        }
        Self {
            buffer: vec.into_boxed_slice(),
            mask: cap - 1,
            write_idx: AtomicUsize::new(0),
            read_idx: AtomicUsize::new(0),
            reconnect_needed: AtomicBool::new(false),
            last_frame_plc: UnsafeCell::new(vec![0i16; frame_samples]),
        }
    }

    #[inline]
    pub fn available_to_read(&self) -> usize {
        let w = self.write_idx.load(Ordering::Acquire);
        let r = self.read_idx.load(Ordering::Relaxed);
        w.wrapping_sub(r)
    }

    #[inline]
    pub fn available_to_write(&self) -> usize {
        (self.mask + 1).saturating_sub(self.available_to_read())
    }

    /// Producer push: writes PCM samples into the ringbuffer.
    /// If queued audio exceeds `max_queued_samples` (~40ms), advances `read_idx` is avoided
    /// by dropping the oldest excess from the producer side or skipping write to prevent latency buildup.
    pub fn push_samples(&self, samples: &[i16], max_queued_samples: usize) {
        if self.available_to_read() > max_queued_samples {
            // Prevent audio latency buildup (>40ms) without touching consumer's read_idx
            return;
        }
        let avail = self.available_to_write();
        let to_write = samples.len().min(avail);
        if to_write == 0 {
            return;
        }
        let mut w = self.write_idx.load(Ordering::Relaxed);
        for &s in &samples[..to_write] {
            unsafe {
                *self.buffer[w & self.mask].get() = s;
            }
            w = w.wrapping_add(1);
        }
        self.write_idx.store(w, Ordering::Release);

        // Save last frame for PLC (Packet Loss Concealment)
        unsafe {
            let plc = &mut *self.last_frame_plc.get();
            if plc.len() == samples.len() {
                plc.copy_from_slice(samples);
            }
        }
    }

    /// Generates a damped Packet Loss Concealment (PLC) frame when an RTP packet is lost.
    pub fn push_plc_frame(&self, max_queued_samples: usize) {
        unsafe {
            let plc = &mut *self.last_frame_plc.get();
            for s in plc.iter_mut() {
                *s = ((*s as i32 * 3) / 4) as i16;
            }
            let copy = plc.clone();
            self.push_samples(&copy, max_queued_samples);
        }
    }

    /// Consumer pop: called inside real-time `AAudioStream_dataCallback`.
    /// Lock-free, zero allocations. Fills missing samples with silence (0).
    #[inline]
    pub fn pop_into(&self, out: &mut [i16]) {
        let w = self.write_idx.load(Ordering::Acquire);
        let mut r = self.read_idx.load(Ordering::Relaxed);
        let avail = w.wrapping_sub(r);
        let to_read = out.len().min(avail);

        for i in 0..to_read {
            unsafe {
                out[i] = *self.buffer[r & self.mask].get();
            }
            r = r.wrapping_add(1);
        }
        for i in to_read..out.len() {
            out[i] = 0;
        }
        if to_read > 0 {
            self.read_idx.store(r, Ordering::Release);
        }
    }

    #[inline]
    pub fn request_reconnect(&self) {
        self.reconnect_needed.store(true, Ordering::Release);
    }

    #[inline]
    pub fn take_reconnect_request(&self) -> bool {
        self.reconnect_needed.swap(false, Ordering::AcqRel)
    }
}

/// Builds RFC 7845 `OpusHead` Codec-Specific Data (`csd-0`) for Android `AMediaCodec("audio/opus")`.
pub fn build_opus_head(channels: u8, sample_rate: u32) -> Vec<u8> {
    let mut head = Vec::with_capacity(27);
    head.extend_from_slice(b"OpusHead");
    head.push(1); // Version 1
    head.push(channels);
    head.extend_from_slice(&312u16.to_le_bytes()); // Pre-skip (312 samples @ 48kHz)
    head.extend_from_slice(&sample_rate.to_le_bytes());
    head.extend_from_slice(&0i16.to_le_bytes()); // Output gain 0 dB
    if channels <= 2 {
        head.push(0); // Channel mapping family 0 (RTP stereo/mono)
    } else {
        head.push(1); // Surround mapping family 1
        let (streams, coupled, map): (u8, u8, &[u8]) = if channels == 6 {
            (4, 2, &[0, 4, 1, 2, 3, 5])
        } else {
            (5, 3, &[0, 6, 1, 2, 3, 4, 5, 7])
        };
        head.push(streams);
        head.push(coupled);
        head.extend_from_slice(map);
    }
    head
}

#[cfg(target_os = "android")]
unsafe extern "C" fn aaudio_data_callback(
    _stream: *mut AAudioStream,
    user_data: *mut c_void,
    audio_data: *mut c_void,
    num_frames: i32,
) -> aaudio_data_callback_result_t {
    if user_data.is_null() || audio_data.is_null() || num_frames <= 0 {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    let ctx = &*(user_data as *const AAudioCallbackContext);
    let total_samples = (num_frames as usize).saturating_mul(ctx.channel_count);
    let out_slice = std::slice::from_raw_parts_mut(audio_data as *mut i16, total_samples);
    ctx.ring.pop_into(out_slice);
    AAUDIO_CALLBACK_RESULT_CONTINUE
}

#[cfg(target_os = "android")]
unsafe extern "C" fn aaudio_error_callback(
    _stream: *mut AAudioStream,
    user_data: *mut c_void,
    error: aaudio_result_t,
) {
    if user_data.is_null() {
        return;
    }
    if error == AAUDIO_ERROR_DISCONNECTED {
        let ctx = &*(user_data as *const AAudioCallbackContext);
        ctx.ring.request_reconnect();
    }
}

pub struct AAudioCallbackContext {
    pub ring: Arc<SpscAudioRingBuffer>,
    pub channel_count: usize,
}

pub struct NdkAudioEngine {
    pub ring: Arc<SpscAudioRingBuffer>,
    pub channel_count: usize,
    pub sample_rate: i32,
    pub max_queued_samples: usize,
    #[cfg(target_os = "android")]
    stream: *mut AAudioStream,
    #[cfg(target_os = "android")]
    cb_ctx: *mut AAudioCallbackContext,
    #[cfg(target_os = "android")]
    opus_codec: *mut AMediaCodec,
}

unsafe impl Send for NdkAudioEngine {}

impl NdkAudioEngine {
    pub fn new(channel_count: usize, sample_rate: i32, samples_per_frame: usize) -> Result<Self, i32> {
        let frame_samples = channel_count * samples_per_frame;
        // 40ms maximum queued audio (= 8 frames of 5ms)
        let max_queued_samples = frame_samples * 8;
        let ring = Arc::new(SpscAudioRingBuffer::new(frame_samples * 32, frame_samples));

        #[cfg(target_os = "android")]
        unsafe {
            let cb_ctx = Box::into_raw(Box::new(AAudioCallbackContext {
                ring: Arc::clone(&ring),
                channel_count,
            }));

            let stream = match Self::open_aaudio_stream(sample_rate, channel_count as i32, cb_ctx) {
                Ok(s) => s,
                Err(e) => {
                    let _ = Box::from_raw(cb_ctx);
                    return Err(e);
                }
            };

            let opus_codec = Self::create_opus_mediacodec(channel_count as u8, sample_rate as u32);

            Ok(Self {
                ring,
                channel_count,
                sample_rate,
                max_queued_samples,
                stream,
                cb_ctx,
                opus_codec,
            })
        }

        #[cfg(not(target_os = "android"))]
        {
            Ok(Self {
                ring,
                channel_count,
                sample_rate,
                max_queued_samples,
            })
        }
    }

    #[cfg(target_os = "android")]
    unsafe fn open_aaudio_stream(
        sample_rate: i32,
        channel_count: i32,
        cb_ctx: *mut AAudioCallbackContext,
    ) -> Result<*mut AAudioStream, i32> {
        let mut builder: *mut AAudioStreamBuilder = std::ptr::null_mut();
        if AAudio_createStreamBuilder(&mut builder) != AAUDIO_OK || builder.is_null() {
            return Err(-10);
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSampleRate(builder, sample_rate);
        AAudioStreamBuilder_setChannelCount(builder, channel_count);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setDataCallback(builder, aaudio_data_callback, cb_ctx as *mut c_void);
        AAudioStreamBuilder_setErrorCallback(builder, aaudio_error_callback, cb_ctx as *mut c_void);

        let mut stream: *mut AAudioStream = std::ptr::null_mut();
        let rc = AAudioStreamBuilder_openStream(builder, &mut stream);
        AAudioStreamBuilder_delete(builder);

        if rc != AAUDIO_OK || stream.is_null() {
            return Err(rc);
        }

        let start_rc = AAudioStream_requestStart(stream);
        if start_rc != AAUDIO_OK {
            AAudioStream_close(stream);
            return Err(start_rc);
        }

        Ok(stream)
    }

    #[cfg(target_os = "android")]
    unsafe fn create_opus_mediacodec(channels: u8, sample_rate: u32) -> *mut AMediaCodec {
        let mime = CString::new("audio/opus").unwrap();
        let codec = AMediaCodec_createDecoderByType(mime.as_ptr());
        if codec.is_null() {
            return std::ptr::null_mut();
        }

        let fmt = AMediaFormat_new();
        let k_mime = CString::new("mime").unwrap();
        let k_sr = CString::new("sample-rate").unwrap();
        let k_ch = CString::new("channel-count").unwrap();
        let k_csd0 = CString::new("csd-0").unwrap();
        let k_csd1 = CString::new("csd-1").unwrap();
        let k_csd2 = CString::new("csd-2").unwrap();

        AMediaFormat_setString(fmt, k_mime.as_ptr(), mime.as_ptr());
        AMediaFormat_setInt32(fmt, k_sr.as_ptr(), sample_rate as i32);
        AMediaFormat_setInt32(fmt, k_ch.as_ptr(), channels as i32);

        let opus_head = build_opus_head(channels, sample_rate);
        let pre_skip_ns: i64 = 6_500_000;
        let seek_preroll_ns: i64 = 80_000_000;
        AMediaFormat_setBuffer(
            fmt,
            k_csd0.as_ptr(),
            opus_head.as_ptr() as *const c_void,
            opus_head.len(),
        );
        AMediaFormat_setBuffer(
            fmt,
            k_csd1.as_ptr(),
            &pre_skip_ns as *const i64 as *const c_void,
            8,
        );
        AMediaFormat_setBuffer(
            fmt,
            k_csd2.as_ptr(),
            &seek_preroll_ns as *const i64 as *const c_void,
            8,
        );

        if AMediaCodec_configure(codec, fmt, std::ptr::null_mut(), std::ptr::null_mut(), 0) != AMEDIA_OK
            || AMediaCodec_start(codec) != AMEDIA_OK
        {
            AMediaFormat_delete(fmt);
            AMediaCodec_delete(codec);
            return std::ptr::null_mut();
        }

        AMediaFormat_delete(fmt);
        codec
    }

    /// Checks if `AAUDIO_ERROR_DISCONNECTED` occurred (e.g. headphones plugged/unplugged)
    /// and transparently recreates the low-latency `AAudioStream` outside the callback thread.
    pub fn check_and_handle_reconnect(&mut self) {
        if !self.ring.take_reconnect_request() {
            return;
        }
        #[cfg(target_os = "android")]
        unsafe {
            if !self.stream.is_null() {
                let _ = AAudioStream_requestStop(self.stream);
                let _ = AAudioStream_close(self.stream);
                self.stream = std::ptr::null_mut();
            }
            if let Ok(new_stream) =
                Self::open_aaudio_stream(self.sample_rate, self.channel_count as i32, self.cb_ctx)
            {
                self.stream = new_stream;
            }
        }
    }

    /// Decodes an Opus packet via NDK `AMediaCodec("audio/opus")` and pushes PCM samples
    /// into the lock-free SPSC ringbuffer.
    pub fn decode_and_enqueue_opus(&mut self, opus_packet: &[u8], pts_us: u64) {
        self.check_and_handle_reconnect();

        #[cfg(target_os = "android")]
        unsafe {
            if self.opus_codec.is_null() {
                return;
            }

            let in_idx = AMediaCodec_dequeueInputBuffer(self.opus_codec, 2000);
            if in_idx >= 0 {
                let mut buf_size = 0usize;
                let buf_ptr = AMediaCodec_getInputBuffer(self.opus_codec, in_idx as usize, &mut buf_size);
                if !buf_ptr.is_null() && buf_size >= opus_packet.len() {
                    std::ptr::copy_nonoverlapping(opus_packet.as_ptr(), buf_ptr, opus_packet.len());
                    let _ = AMediaCodec_queueInputBuffer(
                        self.opus_codec,
                        in_idx as usize,
                        0,
                        opus_packet.len(),
                        pts_us,
                        0,
                    );
                } else {
                    let _ = AMediaCodec_queueInputBuffer(self.opus_codec, in_idx as usize, 0, 0, pts_us, 0);
                }
            }

            let mut info = AMediaCodecBufferInfo::default();
            loop {
                let out_idx = AMediaCodec_dequeueOutputBuffer(self.opus_codec, &mut info, 0);
                if out_idx >= 0 {
                    let mut out_size = 0usize;
                    let out_ptr =
                        AMediaCodec_getOutputBuffer(self.opus_codec, out_idx as usize, &mut out_size);
                    if !out_ptr.is_null() && info.size > 0 {
                        let byte_offset = info.offset.max(0) as usize;
                        let byte_len = (info.size as usize).min(out_size.saturating_sub(byte_offset));
                        let sample_count = byte_len / 2;
                        let pcm_slice = std::slice::from_raw_parts(
                            out_ptr.add(byte_offset) as *const i16,
                            sample_count,
                        );
                        self.ring.push_samples(pcm_slice, self.max_queued_samples);
                    }
                    let _ = AMediaCodec_releaseOutputBuffer(self.opus_codec, out_idx as usize, false);
                } else {
                    break;
                }
            }
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = (opus_packet, pts_us);
        }
    }

    pub fn on_packet_lost(&mut self) {
        self.check_and_handle_reconnect();
        self.ring.push_plc_frame(self.max_queued_samples);
    }

    pub fn pending_duration_ms(&self) -> i32 {
        let samples = self.ring.available_to_read();
        let samples_per_ms = (self.sample_rate as usize / 1000).saturating_mul(self.channel_count).max(1);
        (samples / samples_per_ms) as i32
    }
}

impl Drop for NdkAudioEngine {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        unsafe {
            if !self.stream.is_null() {
                let _ = AAudioStream_requestStop(self.stream);
                let _ = AAudioStream_close(self.stream);
                self.stream = std::ptr::null_mut();
            }
            if !self.cb_ctx.is_null() {
                let _ = Box::from_raw(self.cb_ctx);
                self.cb_ctx = std::ptr::null_mut();
            }
            if !self.opus_codec.is_null() {
                let _ = AMediaCodec_stop(self.opus_codec);
                let _ = AMediaCodec_delete(self.opus_codec);
                self.opus_codec = std::ptr::null_mut();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_spsc_ringbuffer_and_plc() {
        let ring = SpscAudioRingBuffer::new(1024, 480);
        let input: Vec<i16> = (0..480).map(|i| (i * 10) as i16).collect();
        ring.push_samples(&input, 480 * 4);
        assert_eq!(ring.available_to_read(), 480);

        let mut out = vec![0i16; 480];
        ring.pop_into(&mut out);
        assert_eq!(out, input);
        assert_eq!(ring.available_to_read(), 0);

        // Test PLC frame decay
        ring.push_plc_frame(480 * 4);
        assert_eq!(ring.available_to_read(), 480);
        ring.pop_into(&mut out);
        assert_eq!(out[10], (100 * 3) / 4);
    }

    #[test]
    fn test_opus_head_generation() {
        let stereo = build_opus_head(2, 48000);
        assert_eq!(&stereo[..8], b"OpusHead");
        assert_eq!(stereo.len(), 19);

        let surround51 = build_opus_head(6, 48000);
        assert_eq!(&surround51[..8], b"OpusHead");
        assert_eq!(surround51[18], 1); // Mapping family 1
        assert_eq!(surround51.len(), 27);
    }
}
