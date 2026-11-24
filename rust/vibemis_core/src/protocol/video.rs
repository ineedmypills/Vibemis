//! Pure Rust Video Stream Receiver & Annex-B NALU Depacketizer.
//! Pure Rust RTP video receiver and NALU depacketizer for Vibemis.

use crate::jni_bridge;
use crate::render::ndk_video::{has_native_video_surface, NdkVideoDecoder};
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const BUFFER_TYPE_PICDATA: i32 = 0x00;
pub const BUFFER_TYPE_SPS: i32 = 0x01;
pub const BUFFER_TYPE_PPS: i32 = 0x02;
pub const BUFFER_TYPE_VPS: i32 = 0x03;

pub const FRAME_TYPE_PFRAME: i32 = 0x00;
pub const FRAME_TYPE_IDR: i32 = 0x01;

pub const VIDEO_FORMAT_MASK_H264: i32 = 0x000F;
pub const VIDEO_FORMAT_MASK_H265: i32 = 0x0F00;

/// Patches an H.264 Annex-B SPS NALU header (`profile_idc`, `constraint_set4/5`, `level_idc`)
/// in pure Rust without requiring external Java bitstream libraries.
pub fn patch_h264_sps(
    sps_nalu: &[u8],
    width: i32,
    height: i32,
    refresh_rate: i32,
    ref_frame_invalidation_active: bool,
    needs_baseline_hack: bool,
    constrained_high_profile: bool,
) -> Vec<u8> {
    let mut out = sps_nalu.to_vec();
    let start_seq_len = if out.len() >= 4 && out[0] == 0 && out[1] == 0 && out[2] == 1 {
        3
    } else if out.len() >= 5 && out[0] == 0 && out[1] == 0 && out[2] == 0 && out[3] == 1 {
        4
    } else {
        return out;
    };

    // H.264 SPS RBSP starts at start_seq_len + 1:
    // [0] = profile_idc (u8)
    // [1] = constraint_set_flags (u8)
    // [2] = level_idc (u8)
    if out.len() < start_seq_len + 4 {
        return out;
    }

    let profile_idx = start_seq_len + 1;
    let constraint_idx = start_seq_len + 2;
    let level_idx = start_seq_len + 3;

    if !ref_frame_invalidation_active && refresh_rate <= 60 {
        if width <= 720 && height <= 480 {
            out[level_idx] = 31;
        } else if width <= 1280 && height <= 720 {
            out[level_idx] = 32;
        } else if width <= 1920 && height <= 1080 {
            out[level_idx] = 42;
        }
    }

    if needs_baseline_hack {
        out[profile_idx] = 66;
    }

    if out[profile_idx] == 100 && constrained_high_profile {
        // constraint_set4_flag (bit 3 = 0x08) | constraint_set5_flag (bit 2 = 0x04)
        out[constraint_idx] |= 0x0C;
    } else {
        out[constraint_idx] &= !0x0C;
    }

    out
}

#[allow(dead_code)]
const FLAG_CONTAINS_PIC_DATA: u8 = 0x01;
const FLAG_EOF: u8 = 0x02;
const FLAG_SOF: u8 = 0x04;
const FIXED_RTP_HEADER_SIZE: usize = 12;
const NV_VIDEO_HEADER_SIZE: usize = 16;

pub const BUFFER_TYPE_AUD: i32 = -1;

pub fn classify_nalu_type(video_format: i32, nal_header_byte: u8) -> i32 {
    if (video_format & VIDEO_FORMAT_MASK_H264) != 0 {
        match nal_header_byte & 0x1F {
            9 => BUFFER_TYPE_AUD,
            7 => BUFFER_TYPE_SPS,
            8 => BUFFER_TYPE_PPS,
            _ => BUFFER_TYPE_PICDATA,
        }
    } else if (video_format & VIDEO_FORMAT_MASK_H265) != 0 {
        match (nal_header_byte >> 1) & 0x3F {
            35 => BUFFER_TYPE_AUD,
            32 => BUFFER_TYPE_VPS,
            33 => BUFFER_TYPE_SPS,
            34 => BUFFER_TYPE_PPS,
            _ => BUFFER_TYPE_PICDATA,
        }
    } else {
        BUFFER_TYPE_PICDATA
    }
}

pub fn is_idr_frame(video_format: i32, frame_data: &[u8]) -> bool {
    let mut i = 0;
    while i + 3 < frame_data.len() {
        let nalu_start = if frame_data[i] == 0 && frame_data[i + 1] == 0 {
            if frame_data[i + 2] == 1 {
                Some(i + 3)
            } else if i + 4 < frame_data.len() && frame_data[i + 2] == 0 && frame_data[i + 3] == 1 {
                Some(i + 4)
            } else {
                None
            }
        } else {
            None
        };

        if let Some(hdr_idx) = nalu_start {
            if hdr_idx < frame_data.len() {
                let header_byte = frame_data[hdr_idx];
                if (video_format & VIDEO_FORMAT_MASK_H264) != 0 {
                    let nal_type = header_byte & 0x1F;
                    // Type 7 = SPS, Type 5 = IDR slice
                    if nal_type == 7 || nal_type == 5 {
                        return true;
                    }
                } else if (video_format & VIDEO_FORMAT_MASK_H265) != 0 {
                    let nal_type = (header_byte >> 1) & 0x3F;
                    // Type 32 = VPS, Type 33 = SPS, 16..=21 = BLA/CRA/IDR slices
                    if nal_type == 32 || nal_type == 33 || (16..=21).contains(&nal_type) {
                        return true;
                    }
                }
            }
            i = hdr_idx;
        } else {
            i += 1;
        }
    }
    false
}

pub fn split_annex_b_nalus(video_format: i32, frame_data: &[u8]) -> Vec<(i32, Vec<u8>)> {
    let mut starts = Vec::new();
    let mut pic_data = Vec::new();
    let mut units = Vec::new();
    split_annex_b_nalus_with_scratch(
        video_format,
        frame_data,
        &mut starts,
        &mut pic_data,
        |btype, bytes| {
            units.push((btype, bytes.to_vec()));
        },
    );
    units
}

pub fn split_annex_b_nalus_with_scratch<F>(
    video_format: i32,
    frame_data: &[u8],
    starts: &mut Vec<(usize, usize)>,
    pic_data: &mut Vec<u8>,
    mut emit: F,
) where
    F: FnMut(i32, &[u8]),
{
    if (video_format & (VIDEO_FORMAT_MASK_H264 | VIDEO_FORMAT_MASK_H265)) == 0 {
        emit(BUFFER_TYPE_PICDATA, frame_data);
        return;
    }

    starts.clear();
    let mut i = 0;
    while i + 3 < frame_data.len() {
        if frame_data[i] == 0 && frame_data[i + 1] == 0 {
            if frame_data[i + 2] == 1 {
                starts.push((i, i + 3));
                i += 3;
                continue;
            } else if i + 4 < frame_data.len() && frame_data[i + 2] == 0 && frame_data[i + 3] == 1 {
                starts.push((i, i + 4));
                i += 4;
                continue;
            }
        }
        i += 1;
    }

    if starts.is_empty() {
        emit(BUFFER_TYPE_PICDATA, frame_data);
        return;
    }

    pic_data.clear();

    for idx in 0..starts.len() {
        let (nal_start, hdr_idx) = starts[idx];
        let nal_end = if idx + 1 < starts.len() {
            starts[idx + 1].0
        } else {
            frame_data.len()
        };
        if hdr_idx >= nal_end {
            continue;
        }
        let btype = classify_nalu_type(video_format, frame_data[hdr_idx]);
        let slice = &frame_data[nal_start..nal_end];
        if btype == BUFFER_TYPE_AUD {
            // Drop Access Unit Delimiters as required by VideoDepacketizer.c
            continue;
        } else if btype == BUFFER_TYPE_PICDATA {
            pic_data.extend_from_slice(slice);
        } else {
            emit(btype, slice);
        }
    }

    if !pic_data.is_empty() {
        emit(BUFFER_TYPE_PICDATA, pic_data.as_slice());
    }
}

#[derive(Default, Clone)]
pub struct FecBlock {
    pub data_packets: usize,
    pub received_data_count: usize,
    pub data_shards: Vec<Option<Vec<u8>>>,
    pub parity_shards: Vec<(usize, Vec<u8>)>, // (fec_index, data)
    pub fec_percentage: usize,
    pub max_shard_len: usize,
    pub has_eof: bool,
}

/// Helper to assemble fragmented RTP video packets into full Annex-B frames.
/// Handles multi-block FEC, parity packet filtering, Reed-Solomon erasure recovery,
/// packet reordering by fec_index, and SOF frame header stripping.
pub struct VideoFrameAssembler {
    pub current_frame_idx: u32,
    pub frame_receive_ms: u64,
    pub last_completed_frame_idx: u32,
    pub last_completed_receive_ms: u64,
    pub last_block_idx: usize,
    pub blocks: Vec<FecBlock>,
    pub has_sof: bool,
    pub has_eof: bool,
    pub last_push_dropped_frame: bool,
    free_shards: Vec<Vec<u8>>,
}

impl Default for VideoFrameAssembler {
    fn default() -> Self {
        Self {
            current_frame_idx: u32::MAX,
            frame_receive_ms: 0,
            last_completed_frame_idx: 0,
            last_completed_receive_ms: 0,
            last_block_idx: 0,
            blocks: vec![FecBlock::default()],
            has_sof: false,
            has_eof: false,
            last_push_dropped_frame: false,
            free_shards: Vec::with_capacity(64),
        }
    }
}

impl VideoFrameAssembler {
    pub fn new() -> Self {
        Self::default()
    }

    fn alloc_shard_from_slice(&mut self, payload: &[u8]) -> Vec<u8> {
        let mut buf = self.free_shards.pop().unwrap_or_else(|| Vec::with_capacity(1500));
        buf.clear();
        buf.extend_from_slice(payload);
        buf
    }

    /// Process a packet. Returns Some(assembled_frame_bytes) if a frame was completed.
    pub fn push_packet(
        &mut self,
        frame_index: u32,
        flags: u8,
        multi_fec_blocks: u8,
        fec_info: u32,
        payload: &[u8],
        now_ms: u64,
    ) -> Option<Vec<u8>> {
        let mut out = Vec::new();
        if self.push_packet_into(frame_index, flags, multi_fec_blocks, fec_info, payload, now_ms, &mut out) {
            Some(out)
        } else {
            None
        }
    }

    /// Process a packet into a caller-owned reusable frame buffer to eliminate heap allocations.
    pub fn push_packet_into(
        &mut self,
        frame_index: u32,
        flags: u8,
        multi_fec_blocks: u8,
        fec_info: u32,
        payload: &[u8],
        now_ms: u64,
        out_frame: &mut Vec<u8>,
    ) -> bool {
        self.last_push_dropped_frame = false;
        let fec_current_block = ((multi_fec_blocks >> 4) & 0x03) as usize;
        let fec_last_block = ((multi_fec_blocks >> 6) & 0x03) as usize;
        let fec_index = ((fec_info & 0x003FF000) >> 12) as usize;
        let data_packets = ((fec_info & 0xFFC00000) >> 22) as usize;
        let fec_percentage = ((fec_info & 0x00000FF0) >> 4) as usize;

        let mut completed = false;

        // If new frame begins, flush or reset previous frame state
        if frame_index != self.current_frame_idx {
            let had_partial = self.current_frame_idx != u32::MAX
                && self
                    .blocks
                    .iter()
                    .any(|b| b.received_data_count > 0 || !b.parity_shards.is_empty());
            if self.is_ready_to_assemble() {
                completed = self.assemble_current_frame_into(out_frame);
                if completed {
                    self.last_completed_frame_idx = self.current_frame_idx;
                    self.last_completed_receive_ms = self.frame_receive_ms;
                }
            }
            if had_partial && !completed {
                self.last_push_dropped_frame = true;
            }
            self.reset(frame_index, fec_last_block, now_ms);
        } else if fec_last_block > self.last_block_idx {
            self.last_block_idx = fec_last_block;
            if self.blocks.len() <= fec_last_block {
                self.blocks.resize_with(fec_last_block + 1, FecBlock::default);
            }
        }

        if (flags & FLAG_SOF) != 0 && fec_current_block == 0 {
            self.has_sof = true;
        }
        if (flags & FLAG_EOF) != 0 && fec_current_block == fec_last_block {
            self.has_eof = true;
        }

        if self.blocks.len() <= fec_current_block {
            self.blocks.resize_with(fec_current_block + 1, FecBlock::default);
        }

        if fec_current_block < self.blocks.len() {
            let (data_packets_cur, block_len_check, is_data, need_alloc) = {
                let block = &mut self.blocks[fec_current_block];
                if (flags & FLAG_EOF) != 0 {
                    block.has_eof = true;
                }
                if block.data_packets == 0 && data_packets > 0 {
                    block.data_packets = data_packets;
                    block.fec_percentage = fec_percentage;
                    if block.data_shards.len() < data_packets {
                        block.data_shards.resize(data_packets, None);
                    }
                }
                block.max_shard_len = block.max_shard_len.max(payload.len());

                if block.data_packets > 0 {
                    if fec_index < block.data_packets {
                        if block.data_shards.len() <= fec_index {
                            block.data_shards.resize(block.data_packets, None);
                        }
                        (true, fec_index, true, block.data_shards[fec_index].is_none())
                    } else {
                        (
                            true,
                            fec_index,
                            false,
                            !block.parity_shards.iter().any(|(idx, _)| *idx == fec_index),
                        )
                    }
                } else {
                    if block.data_shards.len() <= fec_index {
                        block.data_shards.resize(fec_index + 1, None);
                    }
                    (false, fec_index, true, block.data_shards[fec_index].is_none())
                }
            };

            if need_alloc {
                let shard_buf = self.alloc_shard_from_slice(payload);
                let block = &mut self.blocks[fec_current_block];
                if data_packets_cur && !is_data {
                    block.parity_shards.push((block_len_check, shard_buf));
                } else {
                    block.received_data_count += 1;
                    block.data_shards[block_len_check] = Some(shard_buf);
                }
            }
        }

        if !completed && self.is_ready_to_assemble() {
            if self.assemble_current_frame_into(out_frame) {
                self.last_completed_frame_idx = self.current_frame_idx;
                self.last_completed_receive_ms = self.frame_receive_ms;
                self.reset(frame_index.wrapping_add(1), 0, now_ms);
                completed = true;
            }
        }

        completed
    }

    fn reset(&mut self, frame_index: u32, last_block_idx: usize, now_ms: u64) {
        for block in &mut self.blocks {
            for slot in &mut block.data_shards {
                if let Some(mut buf) = slot.take() {
                    if self.free_shards.len() < 256 {
                        buf.clear();
                        self.free_shards.push(buf);
                    }
                }
            }
            for (_, mut buf) in block.parity_shards.drain(..) {
                if self.free_shards.len() < 256 {
                    buf.clear();
                    self.free_shards.push(buf);
                }
            }
            block.data_packets = 0;
            block.received_data_count = 0;
            block.fec_percentage = 0;
            block.max_shard_len = 0;
            block.has_eof = false;
        }
        self.current_frame_idx = frame_index;
        self.frame_receive_ms = now_ms;
        self.last_block_idx = last_block_idx;
        self.blocks.resize_with(last_block_idx + 1, FecBlock::default);
        self.has_sof = false;
        self.has_eof = false;
    }

    fn is_ready_to_assemble(&self) -> bool {
        if self.blocks.is_empty() || self.blocks.len() <= self.last_block_idx {
            return false;
        }

        for b in 0..=self.last_block_idx {
            let block = &self.blocks[b];
            if block.data_packets > 0 {
                let total_available = block.received_data_count + block.parity_shards.len();
                if total_available < block.data_packets {
                    return false;
                }
            } else {
                if !block.has_eof || block.received_data_count == 0 {
                    return false;
                }
            }
        }
        true
    }

    fn assemble_current_frame_into(&mut self, frame_bytes: &mut Vec<u8>) -> bool {
        for b in 0..=self.last_block_idx {
            let block = &mut self.blocks[b];
            if block.data_packets > 0 && block.received_data_count < block.data_packets {
                let k = block.data_packets;
                let calc_parity = if block.fec_percentage > 0 {
                    (k * block.fec_percentage + 99) / 100
                } else {
                    0
                };
                let max_p_idx = block
                    .parity_shards
                    .iter()
                    .map(|(idx, _)| idx.saturating_sub(k))
                    .max()
                    .unwrap_or(0);
                let p = calc_parity.max(max_p_idx + 1).max(block.parity_shards.len());

                if block.received_data_count + block.parity_shards.len() >= k
                    && k + p <= 255
                    && block.max_shard_len > 0
                {
                    if let Some(rs) = crate::protocol::fec::ReedSolomon::new(k, p) {
                        let block_size = block.max_shard_len;
                        let mut shards = vec![vec![0u8; block_size]; k + p];
                        let mut marks = vec![true; k + p];

                        for i in 0..k {
                            if let Some(ref d) = block.data_shards[i] {
                                shards[i][..d.len()].copy_from_slice(d);
                                marks[i] = false;
                            }
                        }

                        for &(fec_idx, ref p_data) in &block.parity_shards {
                            let p_idx = fec_idx.saturating_sub(k);
                            if p_idx < p {
                                shards[k + p_idx][..p_data.len()].copy_from_slice(p_data);
                                marks[k + p_idx] = false;
                            }
                        }

                        if rs.reconstruct(&mut shards, &marks) {
                            for i in 0..k {
                                if block.data_shards[i].is_none() {
                                    block.data_shards[i] = Some(shards[i].clone());
                                    block.received_data_count += 1;
                                }
                            }
                        }
                    }
                }
            }

            if block.data_packets > 0 && block.received_data_count < block.data_packets {
                return false;
            }
        }

        frame_bytes.clear();

        for b in 0..=self.last_block_idx {
            let block = &self.blocks[b];
            for (idx, shard_opt) in block.data_shards.iter().enumerate() {
                let shard = match shard_opt {
                    Some(s) => s.as_slice(),
                    None => return false,
                };

                if b == 0 && idx == 0 {
                    // First packet of the frame contains the NV frame header.
                    // >= 7.1.450 / Sunshine uses 8-byte header if starting with 0x01,
                    // or 44-byte header if starting with 0x81.
                    let hdr_size = if shard.starts_with(&[0x01]) && shard.len() >= 8 {
                        8
                    } else if shard.starts_with(&[0x81]) && shard.len() >= 44 {
                        44
                    } else {
                        // Fallback Annex-B start sequence search [0, 0, 0, 1] or [0, 0, 1]
                        let limit = shard.len().min(64);
                        let mut found = 0;
                        for i in 0..limit.saturating_sub(2) {
                            if shard[i] == 0 && shard[i + 1] == 0 {
                                if shard[i + 2] == 1 {
                                    found = i;
                                    break;
                                } else if i + 3 < limit && shard[i + 2] == 0 && shard[i + 3] == 1 {
                                    found = i;
                                    break;
                                }
                            }
                        }
                        found
                    };

                    if hdr_size < shard.len() {
                        frame_bytes.extend_from_slice(&shard[hdr_size..]);
                    }
                } else {
                    // Non-first packets contain purely video slice data. Do NOT strip 8 bytes!
                    frame_bytes.extend_from_slice(shard);
                }
            }
        }

        !frame_bytes.is_empty()
    }
}

pub struct VideoStreamHandle {
    stop_flag: Arc<AtomicBool>,
    pending_frames: Arc<AtomicU32>,
    recv_thread: Option<JoinHandle<()>>,
    used_jni_decoder: bool,
}

impl VideoStreamHandle {
    pub fn start(
        remote_addr: SocketAddr,
        video_format: i32,
        width: i32,
        height: i32,
        fps: i32,
        ping_payload: Option<[u8; 16]>,
        idr_flag: Option<Arc<AtomicBool>>,
        stop_flag: Arc<AtomicBool>,
    ) -> Result<Self, i32> {
        // Use MediaCodecDecoderRenderer (Kotlin/Java) as the primary decode + pacing + FSR1 pipeline.
        // Fall back to the pure-NDK decoder only if JNI renderer setup fails.
        let setup_rc = jni_bridge::bridge_dr_setup(video_format, width, height, fps);
        let (ndk_decoder, used_jni_decoder) = if setup_rc == 0 {
            jni_bridge::bridge_dr_start();
            if let Some(ref flag) = idr_flag {
                flag.store(true, Ordering::Release);
            }
            (None, true)
        } else if has_native_video_surface() {
            match NdkVideoDecoder::new(video_format, width, height, fps) {
                Ok(dec) => (Some(dec), false),
                Err(_) => return Err(setup_rc),
            }
        } else {
            return Err(setup_rc);
        };

        let pending_frames = Arc::new(AtomicU32::new(0));
        let pending_clone = Arc::clone(&pending_frames);
        let stop_clone = Arc::clone(&stop_flag);

        let handle = thread::Builder::new()
            .name("RustVideoRecv".into())
            .spawn(move || {
                crate::cpu_affinity::pin_current_thread_to_big_cores();

                let _ndk_dec_guard = ndk_decoder;
                let sock = match UdpSocket::bind("0.0.0.0:0") {
                    Ok(s) => s,
                    Err(_) => return,
                };
                let _ = sock.set_read_timeout(Some(Duration::from_millis(50)));

                let mut last_ping = Instant::now();
                let mut last_idr_req = Instant::now() - Duration::from_secs(1);
                let mut hot_threads_pinned = false;
                let start_instant = Instant::now();
                let mut ping_seq = 0u32;
                let mut assembler = VideoFrameAssembler::new();
                let mut buf = vec![0u8; 2048];
                let mut frame_buf = Vec::with_capacity(256 * 1024);
                let mut starts_scratch = Vec::with_capacity(16);
                let mut pic_scratch = Vec::with_capacity(256 * 1024);

                let mut maybe_request_idr = || {
                    if let Some(ref flag) = idr_flag {
                        if last_idr_req.elapsed() >= Duration::from_millis(100) {
                            last_idr_req = Instant::now();
                            flag.store(true, Ordering::Release);
                        }
                    }
                };

                while !stop_clone.load(Ordering::Relaxed) {
                    if !hot_threads_pinned && start_instant.elapsed() >= Duration::from_millis(1500) {
                        hot_threads_pinned = true;
                        let _ = crate::cpu_affinity::pin_hot_threads_to_big_cores();
                    }

                    if last_ping.elapsed() >= Duration::from_millis(500) {
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
                        if len <= FIXED_RTP_HEADER_SIZE + NV_VIDEO_HEADER_SIZE {
                            continue;
                        }
                        let rtp_hdr = buf[0];
                        let ext_offset = if (rtp_hdr & 0x10) != 0 { 4 } else { 0 };
                        let nv_offset = FIXED_RTP_HEADER_SIZE + ext_offset;
                        if len <= nv_offset + NV_VIDEO_HEADER_SIZE {
                            continue;
                        }

                        let nv_hdr = &buf[nv_offset..nv_offset + NV_VIDEO_HEADER_SIZE];
                        let frame_index = u32::from_le_bytes([nv_hdr[4], nv_hdr[5], nv_hdr[6], nv_hdr[7]]);
                        let flags = nv_hdr[8];
                        let multi_fec_blocks = nv_hdr[11];
                        let fec_info = u32::from_le_bytes([nv_hdr[12], nv_hdr[13], nv_hdr[14], nv_hdr[15]]);
                        let payload_start = nv_offset + NV_VIDEO_HEADER_SIZE;
                        let payload = &buf[payload_start..len];

                        let now_ms = start_instant.elapsed().as_millis() as u64;

                        let assembled = assembler.push_packet_into(
                            frame_index,
                            flags,
                            multi_fec_blocks,
                            fec_info,
                            payload,
                            now_ms,
                            &mut frame_buf,
                        );
                        if assembler.last_push_dropped_frame {
                            maybe_request_idr();
                        }

                        if assembled {
                            pending_clone.fetch_add(1, Ordering::Relaxed);
                            let is_idr = is_idr_frame(video_format, &frame_buf);
                            let ftype = if is_idr { FRAME_TYPE_IDR } else { FRAME_TYPE_PFRAME };
                            let pts_us = now_ms.saturating_mul(1000);
                            let completed_frame_idx = assembler.last_completed_frame_idx;
                            let frame_rx_ms = assembler.last_completed_receive_ms as i64;
                            let mut need_idr = false;

                            split_annex_b_nalus_with_scratch(
                                video_format,
                                &frame_buf,
                                &mut starts_scratch,
                                &mut pic_scratch,
                                |btype, unit_bytes| {
                                    if let Some(ref dec) = _ndk_dec_guard {
                                        let _ = dec.submit_decode_unit(unit_bytes, btype, pts_us);
                                    } else {
                                        let submit_rc = jni_bridge::bridge_dr_submit_decode_unit(
                                            unit_bytes,
                                            btype,
                                            completed_frame_idx as i32,
                                            ftype,
                                            0,
                                            frame_rx_ms,
                                            now_ms as i64,
                                        );
                                        if submit_rc < 0 {
                                            need_idr = true;
                                        }
                                    }
                                },
                            );
                            if need_idr {
                                maybe_request_idr();
                            }
                            pending_clone.fetch_sub(1, Ordering::Relaxed);
                        }
                    }
                }
            })
            .map_err(|_| -1)?;

        Ok(Self {
            stop_flag,
            pending_frames,
            recv_thread: Some(handle),
            used_jni_decoder,
        })
    }

    pub fn pending_frames(&self) -> i32 {
        self.pending_frames.load(Ordering::Relaxed) as i32
    }

    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        if let Some(h) = self.recv_thread.take() {
            let _ = h.join();
        }
        if self.used_jni_decoder {
            jni_bridge::bridge_dr_stop();
            jni_bridge::bridge_dr_cleanup();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_annex_b_nalu_splitting_h264() {
        let mut bitstream = Vec::new();
        // SPS (nal_unit_type = 7 -> 0x67)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E]);
        // PPS (nal_unit_type = 8 -> 0x68)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80]);
        // IDR slice (nal_unit_type = 5 -> 0x65)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21]);

        let units = split_annex_b_nalus(0x0001, &bitstream);
        assert_eq!(units.len(), 3);
        assert_eq!(units[0].0, BUFFER_TYPE_SPS);
        assert_eq!(units[1].0, BUFFER_TYPE_PPS);
        assert_eq!(units[2].0, BUFFER_TYPE_PICDATA);
        assert!(is_idr_frame(0x0001, &bitstream));
    }

    #[test]
    fn test_aud_stripping_and_idr_detection() {
        let mut bitstream = Vec::new();
        // AUD NAL unit (H.264 type 9 -> 0x09)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x09, 0xF0]);
        // SPS (type 7 -> 0x67)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28]);
        // IDR slice (type 5 -> 0x65)
        bitstream.extend_from_slice(&[0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21]);

        let units = split_annex_b_nalus(0x0001, &bitstream);
        // AUD must be stripped, so only SPS and IDR slice remain:
        assert_eq!(units.len(), 2);
        assert_eq!(units[0].0, BUFFER_TYPE_SPS);
        assert_eq!(units[1].0, BUFFER_TYPE_PICDATA);
        assert!(is_idr_frame(0x0001, &bitstream));
    }

    #[test]
    fn test_video_frame_assembler_reordering_and_header_strip() {
        let mut assembler = VideoFrameAssembler::new();

        // 2 data packets:
        // data_packets = 2 (in bits 22..31: 2 << 22 = 0x00800000)
        let fec_info_base = 2u32 << 22;
        let fec_info_p0 = fec_info_base | (0u32 << 12);
        let fec_info_p1 = fec_info_base | (1u32 << 12);

        // P0 has an 8-byte NV frame header [0x01, ...] followed by SPS Annex-B
        let mut p0 = vec![0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00];
        p0.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E]);

        // P1 has slice data [0xAA, 0xBB, 0xCC, 0xDD]
        let p1 = vec![0xAA, 0xBB, 0xCC, 0xDD];

        // Receive P1 FIRST (out-of-order) with FLAG_EOF (0x02)
        let res1 = assembler.push_packet(10, FLAG_EOF, 0, fec_info_p1, &p1, 100);
        assert!(res1.is_none(), "Should wait for complete frame because P0 is missing");

        // Receive P0 with FLAG_SOF (0x04)
        let res0 = assembler.push_packet(10, FLAG_SOF, 0, fec_info_p0, &p0, 102);
        assert!(res0.is_some(), "Frame should be assembled now");

        let frame = res0.unwrap();
        // The 8-byte header from P0 was stripped:
        assert_eq!(&frame[0..8], &[0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E]);
        // P1 was appended in order (shard 1 after shard 0) without stripping:
        assert_eq!(&frame[8..12], &[0xAA, 0xBB, 0xCC, 0xDD]);
        assert_eq!(frame.len(), 12);
    }

    #[test]
    fn test_video_frame_assembler_fec_recovery() {
        let mut assembler = VideoFrameAssembler::new();

        // 2 data shards, 1 parity shard
        let k = 2;
        let p = 1;
        let rs = crate::protocol::fec::ReedSolomon::new(k, p).unwrap();

        let shard0 = vec![0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 10, 20, 30, 40];
        let shard1 = vec![50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160];

        let mut all_shards = vec![shard0.clone(), shard1.clone(), vec![0u8; 12]];
        rs.encode_parity(&mut all_shards);
        let parity_shard = all_shards[2].clone();

        let fec_info_base = (k as u32) << 22;
        let fec_info_p0 = fec_info_base | (0u32 << 12);
        let fec_info_parity = fec_info_base | (2u32 << 12);

        // Packet 1 is LOST in transit!
        // Push P0:
        assembler.push_packet(50, FLAG_SOF, 0, fec_info_p0, &shard0, 200);
        // Push parity packet with EOF:
        let recovered_frame = assembler.push_packet(50, FLAG_EOF, 0, fec_info_parity, &parity_shard, 201);
        assert!(recovered_frame.is_some(), "FEC recovery should reconstruct shard1 and assemble");

        let frame = recovered_frame.unwrap();
        // Shard 0 without 8-byte header = [10, 20, 30, 40]
        assert_eq!(&frame[0..4], &[10, 20, 30, 40]);
        // Shard 1 recovered via RS = [50, 60, ..., 160]
        assert_eq!(&frame[4..16], &shard1);
    }

    #[test]
    fn test_video_frame_assembler_out_of_order_parity_recovery() {
        let mut assembler = VideoFrameAssembler::new();

        // 2 data shards, 2 parity shards (k=2, p=2, 100% fec)
        let k = 2;
        let p = 2;
        let rs = crate::protocol::fec::ReedSolomon::new(k, p).unwrap();

        let shard0 = vec![0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 11, 22, 33, 44];
        let shard1 = vec![55, 66, 77, 88, 99, 111, 122, 133, 144, 155, 166, 177];

        let mut all_shards = vec![shard0.clone(), shard1.clone(), vec![0u8; 12], vec![0u8; 12]];
        rs.encode_parity(&mut all_shards);
        let _parity_0 = all_shards[2].clone(); // LOST in transit
        let parity_1 = all_shards[3].clone(); // Arrives (fec_index = 3)

        // fec_percentage = 100 in bits 4..11: 100 << 4 = 0x640
        let fec_info_base = ((k as u32) << 22) | (100u32 << 4);
        let fec_info_p0 = fec_info_base | (0u32 << 12);
        let fec_info_parity1 = fec_info_base | (3u32 << 12);

        // Data shard 1 is lost! Parity shard 0 is also lost!
        // Parity shard 1 arrives.
        assembler.push_packet(60, FLAG_SOF, 0, fec_info_p0, &shard0, 300);
        let recovered = assembler.push_packet(60, FLAG_EOF, 0, fec_info_parity1, &parity_1, 301);
        assert!(recovered.is_some(), "Should reconstruct using second parity shard at index 3");

        let frame = recovered.unwrap();
        assert_eq!(&frame[0..4], &[11, 22, 33, 44]);
        assert_eq!(&frame[4..16], &shard1);
    }

    #[test]
    fn test_video_frame_assembler_multi_block_fec() {
        let mut assembler = VideoFrameAssembler::new();

        // 2 FEC blocks in this frame:
        // multiFecBlocks: current block in bits 4..5, last block in bits 6..7
        // Block 0: current=0, last=1 -> (1 << 6) | (0 << 4) = 0x40
        // Block 1: current=1, last=1 -> (1 << 6) | (1 << 4) = 0x50
        let blk0_flags = 0x40u8;
        let blk1_flags = 0x50u8;

        // Each block has 1 data packet (k=1)
        let fec_info = 1u32 << 22;

        // Block 0 packet 0 has NV header [0x01, ...] + slice A
        let mut b0_p0 = vec![0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        b0_p0.extend_from_slice(&[1, 2, 3, 4]);

        // Block 1 packet 0 has slice B (no NV header!)
        let b1_p0 = vec![5, 6, 7, 8];

        // Push Block 1 FIRST
        let res_b1 = assembler.push_packet(70, FLAG_EOF, blk1_flags, fec_info, &b1_p0, 400);
        assert!(res_b1.is_none(), "Should not assemble until Block 0 arrives");

        // Push Block 0 with FLAG_SOF
        let res_b0 = assembler.push_packet(70, FLAG_SOF, blk0_flags, fec_info, &b0_p0, 401);
        assert!(res_b0.is_some(), "Multi-block frame should assemble now");

        let frame = res_b0.unwrap();
        // Block 0 stripped 8 bytes -> [1, 2, 3, 4]
        // Block 1 not stripped -> [5, 6, 7, 8]
        assert_eq!(frame, vec![1, 2, 3, 4, 5, 6, 7, 8]);
    }

    #[test]
    fn test_video_frame_assembler_preserves_completed_frame_telemetry() {
        let mut assembler = VideoFrameAssembler::new();
        let fec_info_base = 2u32 << 22;
        let fec_info_p0 = fec_info_base | (0u32 << 12);
        let fec_info_p1 = fec_info_base | (1u32 << 12);

        let mut p0 = vec![0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        p0.extend_from_slice(&[0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E]);
        let p1 = vec![0xAA, 0xBB];

        assert!(assembler.push_packet(42, FLAG_SOF, 0, fec_info_p0, &p0, 1000).is_none());
        let done = assembler.push_packet(42, FLAG_EOF, 0, fec_info_p1, &p1, 1017);
        assert!(done.is_some());
        assert_eq!(assembler.last_completed_frame_idx, 42);
        assert_eq!(assembler.last_completed_receive_ms, 1000);
    }
}

