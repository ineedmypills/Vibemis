//! Pure Rust Audio Stream Receiver, AES-128-CBC Decryption & NDK `AAudio` + `AMediaCodec` Opus Pipeline.
//! Zero JNI calls during audio streaming on Android.

use crate::protocol::crypto::Aes128;
use crate::render::ndk_audio::NdkAudioEngine;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const FIXED_RTP_HEADER_SIZE: usize = 12;

pub struct AudioStreamHandle {
    stop_flag: Arc<AtomicBool>,
    pending_duration_ms: Arc<AtomicU32>,
    recv_thread: Option<JoinHandle<()>>,
}

impl AudioStreamHandle {
    pub fn start(
        remote_addr: SocketAddr,
        audio_configuration: i32,
        ri_aes_key: [u8; 16],
        ri_aes_iv: [u8; 16],
        encrypted: bool,
        ping_payload: Option<[u8; 16]>,
        stop_flag: Arc<AtomicBool>,
    ) -> Result<Self, i32> {
        let sample_rate = 48000;
        let samples_per_frame = 240usize; // 5 ms at 48 kHz
        let channel_count = ((audio_configuration >> 8) & 0xFF).max(2) as usize;

        let mut ndk_engine = NdkAudioEngine::new(channel_count, sample_rate, samples_per_frame).ok();

        let pending_duration_ms = Arc::new(AtomicU32::new(0));
        let pending_clone = Arc::clone(&pending_duration_ms);
        let stop_clone = Arc::clone(&stop_flag);

        let handle = thread::Builder::new()
            .name("RustAudioRecv".into())
            .spawn(move || {
                crate::cpu_affinity::pin_current_thread_to_big_cores();
                let sock = match UdpSocket::bind("0.0.0.0:0") {
                    Ok(s) => s,
                    Err(_) => return,
                };
                let _ = sock.set_read_timeout(Some(Duration::from_millis(100)));
                let cipher = Aes128::new(&ri_aes_key);
                let base_iv =
                    u32::from_be_bytes([ri_aes_iv[0], ri_aes_iv[1], ri_aes_iv[2], ri_aes_iv[3]]);

                let mut ping_seq = 0u32;
                let mut last_ping = Instant::now() - Duration::from_secs(1);
                let mut last_seq: Option<u16> = None;
                let mut pts_us = 0u64;
                let mut buf = vec![0u8; 2048];
                let mut decrypted_scratch = Vec::with_capacity(2048);

                while !stop_clone.load(Ordering::Relaxed) {
                    if last_ping.elapsed() >= Duration::from_millis(250) {
                        last_ping = Instant::now();
                        ping_seq = ping_seq.wrapping_add(1);
                        if let Some(payload) = ping_payload {
                            let mut ping = [0u8; 20];
                            ping[..16].copy_from_slice(&payload);
                            ping[16..20].copy_from_slice(&ping_seq.to_be_bytes());
                            let _ = sock.send_to(&ping, remote_addr);
                        } else {
                            let _ = sock.send_to(b"PING", remote_addr);
                        }
                    }

                    if let Ok((len, _)) = sock.recv_from(&mut buf) {
                        if len <= FIXED_RTP_HEADER_SIZE {
                            continue;
                        }
                        let seq_u16 = u16::from_be_bytes([buf[2], buf[3]]);
                        let seq = seq_u16 as u32;
                        let payload = &buf[FIXED_RTP_HEADER_SIZE..len];

                        // Detect RTP sequence gaps for Packet Loss Concealment (PLC)
                        if let Some(prev) = last_seq {
                            let gap = seq_u16.wrapping_sub(prev.wrapping_add(1));
                            if gap > 0 && gap <= 4 {
                                if let Some(ref mut eng) = ndk_engine {
                                    for _ in 0..gap {
                                        eng.on_packet_lost();
                                    }
                                }
                            }
                        }
                        last_seq = Some(seq_u16);

                        let audio_slice: &[u8] = if encrypted && payload.len() >= 16 {
                            let mut iv = [0u8; 16];
                            iv[0..4].copy_from_slice(&(base_iv.wrapping_add(seq)).to_be_bytes());
                            cipher.decrypt_cbc_into(&iv, payload, &mut decrypted_scratch);
                            &decrypted_scratch
                        } else {
                            payload
                        };

                        if !audio_slice.is_empty() {
                            if let Some(ref mut eng) = ndk_engine {
                                eng.decode_and_enqueue_opus(audio_slice, pts_us);
                                pending_clone.store(eng.pending_duration_ms() as u32, Ordering::Relaxed);
                            }
                            pts_us = pts_us.wrapping_add(5000);
                        }
                    }
                }
            })
            .map_err(|_| -1)?;

        Ok(Self {
            stop_flag,
            pending_duration_ms,
            recv_thread: Some(handle),
        })
    }

    pub fn pending_duration_ms(&self) -> i32 {
        self.pending_duration_ms.load(Ordering::Relaxed) as i32
    }

    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        if let Some(h) = self.recv_thread.take() {
            let _ = h.join();
        }
    }
}
