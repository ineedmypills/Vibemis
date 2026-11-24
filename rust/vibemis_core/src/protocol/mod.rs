//! Pure Rust Vibemis Connection Manager, Stage & Port Flag Utilities, and Connectivity Tester.

pub mod audio;
pub mod control;
pub mod crypto;
pub mod fec;
pub mod input;
pub mod mdns;
pub mod rtsp;
pub mod sdp;
pub mod stun;
pub mod video;
pub mod wol;

use crate::jni_bridge;
use crate::protocol::audio::AudioStreamHandle;
use crate::protocol::control::{ControlSendHandle, ControlStreamHandle};
use crate::protocol::crypto::Aes128;
use crate::protocol::rtsp::perform_rtsp_handshake;
use crate::protocol::video::VideoStreamHandle;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI16, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::time::Duration;

pub const STAGE_NONE: i32 = 0;
pub const STAGE_PLATFORM_INIT: i32 = 1;
pub const STAGE_NAME_RESOLUTION: i32 = 2;
pub const STAGE_AUDIO_STREAM_INIT: i32 = 3;
pub const STAGE_RTSP_HANDSHAKE: i32 = 4;
pub const STAGE_CONTROL_STREAM_INIT: i32 = 5;
pub const STAGE_VIDEO_STREAM_INIT: i32 = 6;
pub const STAGE_INPUT_STREAM_INIT: i32 = 7;
pub const STAGE_CONTROL_STREAM_START: i32 = 8;
pub const STAGE_VIDEO_STREAM_START: i32 = 9;
pub const STAGE_AUDIO_STREAM_START: i32 = 10;
pub const STAGE_INPUT_STREAM_START: i32 = 11;
pub const STAGE_MAX: i32 = 12;

pub const VB_ERROR_GRACEFUL_TERMINATION: i32 = 0;
pub const VB_ERROR_NO_VIDEO_TRAFFIC: i32 = -100;

pub const VB_PORT_INDEX_TCP_47984: u32 = 0;
pub const VB_PORT_INDEX_TCP_47989: u32 = 1;
pub const VB_PORT_INDEX_TCP_48010: u32 = 2;
pub const VB_PORT_INDEX_UDP_47998: u32 = 8;
pub const VB_PORT_INDEX_UDP_47999: u32 = 9;
pub const VB_PORT_INDEX_UDP_48000: u32 = 10;
pub const VB_PORT_INDEX_UDP_48010: u32 = 11;

pub const VB_PORT_FLAG_ALL: u32 = 0xFFFFFFFF;
pub const VB_PORT_FLAG_TCP_47984: u32 = 0x0001;
pub const VB_PORT_FLAG_TCP_47989: u32 = 0x0002;
pub const VB_PORT_FLAG_TCP_48010: u32 = 0x0004;
pub const VB_PORT_FLAG_UDP_47998: u32 = 0x0100;
pub const VB_PORT_FLAG_UDP_47999: u32 = 0x0200;
pub const VB_PORT_FLAG_UDP_48000: u32 = 0x0400;
pub const VB_PORT_FLAG_UDP_48010: u32 = 0x0800;
pub const VB_TEST_RESULT_INCONCLUSIVE: u32 = 0xFFFFFFFF;

pub const VB_ERR_UNSUPPORTED: i32 = -5501;

const STAGE_NAMES: [&str; STAGE_MAX as usize] = [
    "none",
    "platform initialization",
    "name resolution",
    "audio stream initialization",
    "RTSP handshake",
    "control stream initialization",
    "video stream initialization",
    "input stream initialization",
    "control stream establishment",
    "video stream establishment",
    "audio stream establishment",
    "input stream establishment",
];

pub fn get_stage_name(stage: i32) -> &'static str {
    if stage >= 0 && (stage as usize) < STAGE_NAMES.len() {
        STAGE_NAMES[stage as usize]
    } else {
        "unknown"
    }
}

pub fn get_launch_url_query_parameters() -> &'static str {
    "&corever=1"
}

pub fn get_port_flags_from_stage(stage: i32) -> u32 {
    match stage {
        STAGE_RTSP_HANDSHAKE => {
            VB_PORT_FLAG_TCP_48010 | VB_PORT_FLAG_UDP_48010 | VB_PORT_FLAG_UDP_48000
        }
        STAGE_CONTROL_STREAM_START => VB_PORT_FLAG_UDP_47999,
        _ => 0,
    }
}

pub fn get_port_flags_from_termination_error_code(error_code: i32) -> u32 {
    match error_code {
        VB_ERROR_NO_VIDEO_TRAFFIC => VB_PORT_FLAG_UDP_47998 | VB_PORT_FLAG_UDP_48000,
        _ => 0,
    }
}

pub fn port_from_flag_index(index: u32) -> Option<(bool, u16)> {
    match index {
        VB_PORT_INDEX_TCP_47984 => Some((false, 47984)),
        VB_PORT_INDEX_TCP_47989 => Some((false, 47989)),
        VB_PORT_INDEX_TCP_48010 => Some((false, 48010)),
        VB_PORT_INDEX_UDP_47998 => Some((true, 47998)),
        VB_PORT_INDEX_UDP_47999 => Some((true, 47999)),
        VB_PORT_INDEX_UDP_48000 => Some((true, 48000)),
        VB_PORT_INDEX_UDP_48010 => Some((true, 48010)),
        _ => None,
    }
}

pub fn stringify_port_flags(port_flags: u32, separator: &str) -> String {
    let mut parts = Vec::new();
    for i in 0..32u32 {
        if (port_flags & (1u32 << i)) != 0 {
            if let Some((is_udp, port)) = port_from_flag_index(i) {
                let proto = if is_udp { "UDP" } else { "TCP" };
                parts.push(format!("{proto} {port}"));
            }
        }
    }
    parts.join(separator)
}

pub fn test_client_connectivity(test_server: &str, reference_port: u16, test_port_flags: u32) -> u32 {
    let valid_mask = VB_PORT_FLAG_TCP_47984
        | VB_PORT_FLAG_TCP_47989
        | VB_PORT_FLAG_TCP_48010
        | VB_PORT_FLAG_UDP_47998
        | VB_PORT_FLAG_UDP_47999
        | VB_PORT_FLAG_UDP_48000
        | VB_PORT_FLAG_UDP_48010;
    let flags = test_port_flags & valid_mask;
    if flags == 0 {
        return 0;
    }

    let base_addr = match (test_server, reference_port).to_socket_addrs() {
        Ok(mut it) => match it.next() {
            Some(a) => a,
            None => return VB_TEST_RESULT_INCONCLUSIVE,
        },
        Err(_) => return VB_TEST_RESULT_INCONCLUSIVE,
    };

    if TcpStream::connect_timeout(&base_addr, Duration::from_secs(3)).is_err() {
        return VB_TEST_RESULT_INCONCLUSIVE;
    }

    let mut failing = flags;
    for i in 0..32u32 {
        if (flags & (1u32 << i)) == 0 {
            continue;
        }
        if let Some((is_udp, port)) = port_from_flag_index(i) {
            let target = SocketAddr::new(base_addr.ip(), port);
            if !is_udp {
                if TcpStream::connect_timeout(&target, Duration::from_secs(3)).is_ok() {
                    failing &= !(1u32 << i);
                }
            } else if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
                let _ = sock.set_read_timeout(Some(Duration::from_secs(2)));
                let mut probe = [0u8; 1040];
                probe[..15].copy_from_slice(b"vibemis-nettest");
                let _ = sock.send_to(&probe, target);
                let mut recv_buf = [0u8; 1040];
                if sock.recv_from(&mut recv_buf).is_ok() {
                    failing &= !(1u32 << i);
                }
            }
        }
    }
    failing
}

#[derive(Debug, Clone)]
pub struct ServerInfo {
    pub address: String,
    pub app_version: String,
    pub gfe_version: Option<String>,
    pub rtsp_session_url: Option<String>,
    pub server_codec_mode_support: i32,
}

#[derive(Debug, Clone)]
pub struct StreamConfig {
    pub width: i32,
    pub height: i32,
    pub fps: i32,
    pub bitrate: i32,
    pub packet_size: i32,
    pub streaming_remotely: i32,
    pub audio_configuration: i32,
    pub supported_video_formats: i32,
    pub client_refresh_rate_x100: i32,
    pub color_space: i32,
    pub color_range: i32,
    pub encryption_flags: i32,
    pub ri_aes_key: [u8; 16],
    pub ri_aes_iv: [u8; 16],
    pub video_capabilities: i32,
}

struct InputDispatcher {
    control: Option<ControlSendHandle>,
    input_sock: Option<UdpSocket>,
    control_addr: SocketAddr,
    cipher: Aes128,
    ri_aes_iv: [u8; 16],
    input_seq: AtomicU32,
    virtual_mouse_x: AtomicI16,
    virtual_mouse_y: AtomicI16,
}

impl InputDispatcher {
    fn send_raw(&self, raw_packet: &[u8]) -> i32 {
        if let Some(ref control) = self.control {
            return control.send_input_packet(raw_packet);
        }
        if let Some(ref sock) = self.input_sock {
            let seq = self.input_seq.fetch_add(1, Ordering::Relaxed);
            let encrypted = input::encrypt_input_packet_cbc(
                &self.cipher,
                &self.ri_aes_iv,
                seq,
                raw_packet,
            );
            let _ = sock.send_to(&encrypted, self.control_addr);
            return 0;
        }
        -1
    }
}

struct ActiveSession {
    stop_flag: Arc<AtomicBool>,
    control_handle: Option<ControlStreamHandle>,
    rtt_ms: AtomicU32,
    rtt_var_ms: AtomicU32,
    video_handle: Option<VideoStreamHandle>,
    audio_handle: Option<AudioStreamHandle>,
}

static INTERRUPTED: AtomicBool = AtomicBool::new(false);
static CONTROLLER_SPEAKER_OWNER: AtomicU64 = AtomicU64::new(0);
static NEXT_SPEAKER_OWNER_TOKEN: AtomicU64 = AtomicU64::new(1);
static SESSION: OnceLock<Mutex<Option<ActiveSession>>> = OnceLock::new();
static INPUT_DISPATCH: OnceLock<RwLock<Option<Arc<InputDispatcher>>>> = OnceLock::new();

fn input_dispatch_lock() -> &'static RwLock<Option<Arc<InputDispatcher>>> {
    INPUT_DISPATCH.get_or_init(|| RwLock::new(None))
}

pub fn get_controller_speaker_owner() -> u64 {
    CONTROLLER_SPEAKER_OWNER.load(Ordering::Relaxed)
}

pub fn next_controller_speaker_owner() -> u64 {
    let token = NEXT_SPEAKER_OWNER_TOKEN.fetch_add(1, Ordering::Relaxed).max(1);
    CONTROLLER_SPEAKER_OWNER.store(token, Ordering::Relaxed);
    token
}

pub fn request_idr_frame() {
    if let Ok(guard) = input_dispatch_lock().read() {
        if let Some(ref dispatcher) = *guard {
            if let Some(ref control) = dispatcher.control {
                control.request_idr_frame();
            }
        }
    }
}

fn session_lock() -> &'static Mutex<Option<ActiveSession>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

fn parse_rtsp_port(url: Option<&str>) -> u16 {
    if let Some(u) = url {
        if let Some((_, port_str)) = u.rsplit_once(':') {
            let clean = port_str.trim_end_matches('/');
            if let Ok(p) = clean.parse::<u16>() {
                if p > 0 {
                    return p;
                }
            }
        }
    }
    48010
}

pub fn start_connection(server_info: ServerInfo, mut stream_config: StreamConfig) -> i32 {
    stop_connection();
    INTERRUPTED.store(false, Ordering::SeqCst);

    if server_info.server_codec_mode_support == 0 {
        return -1;
    }
    if (stream_config.audio_configuration & 0xFF) != 0xCA {
        return -1;
    }

    stream_config.packet_size -= stream_config.packet_size % 16;
    if stream_config.packet_size <= 0 {
        return -1;
    }
    if (stream_config.height & 1) != 0 {
        stream_config.height &= !1;
    }

    jni_bridge::bridge_cl_stage_starting(STAGE_PLATFORM_INIT);
    jni_bridge::bridge_cl_stage_complete(STAGE_PLATFORM_INIT);

    jni_bridge::bridge_cl_stage_starting(STAGE_NAME_RESOLUTION);
    let rtsp_port = parse_rtsp_port(server_info.rtsp_session_url.as_deref());
    let resolved_addr = match (server_info.address.as_str(), rtsp_port).to_socket_addrs() {
        Ok(mut addrs) => match addrs.next() {
            Some(a) => a,
            None => {
                jni_bridge::bridge_cl_stage_failed(STAGE_NAME_RESOLUTION, -1);
                return -1;
            }
        },
        Err(_) => {
            jni_bridge::bridge_cl_stage_failed(STAGE_NAME_RESOLUTION, -1);
            return -1;
        }
    };
    jni_bridge::bridge_cl_stage_complete(STAGE_NAME_RESOLUTION);

    if INTERRUPTED.load(Ordering::SeqCst) {
        return -1;
    }

    jni_bridge::bridge_cl_stage_starting(STAGE_AUDIO_STREAM_INIT);
    jni_bridge::bridge_cl_stage_complete(STAGE_AUDIO_STREAM_INIT);

    jni_bridge::bridge_cl_stage_starting(STAGE_RTSP_HANDSHAKE);
    let negotiated = match perform_rtsp_handshake(&server_info, &stream_config, rtsp_port) {
        Ok(n) => n,
        Err(err) => {
            jni_bridge::bridge_cl_stage_failed(STAGE_RTSP_HANDSHAKE, err);
            return err;
        }
    };
    jni_bridge::bridge_cl_stage_complete(STAGE_RTSP_HANDSHAKE);

    for stage in [
        STAGE_CONTROL_STREAM_INIT,
        STAGE_VIDEO_STREAM_INIT,
        STAGE_INPUT_STREAM_INIT,
    ] {
        jni_bridge::bridge_cl_stage_starting(stage);
        jni_bridge::bridge_cl_stage_complete(stage);
    }

    let stop_flag = Arc::new(AtomicBool::new(false));
    let video_addr = SocketAddr::new(resolved_addr.ip(), negotiated.video_port);
    let audio_addr = SocketAddr::new(resolved_addr.ip(), negotiated.audio_port);
    let control_addr = SocketAddr::new(resolved_addr.ip(), negotiated.control_port);

    jni_bridge::bridge_cl_stage_starting(STAGE_CONTROL_STREAM_START);
    let mut control_handle = ControlStreamHandle::start(
        control_addr,
        stream_config.ri_aes_key,
        negotiated.control_connect_data,
        Arc::clone(&stop_flag),
    ).ok();
    jni_bridge::bridge_cl_stage_complete(STAGE_CONTROL_STREAM_START);

    jni_bridge::bridge_cl_stage_starting(STAGE_VIDEO_STREAM_START);
    let negotiated_video_format = if (stream_config.supported_video_formats & 0xF000) != 0 {
        stream_config.supported_video_formats & 0xF000
    } else if (stream_config.supported_video_formats & 0x0F00) != 0 {
        stream_config.supported_video_formats & 0x0F00
    } else if (stream_config.supported_video_formats & 0x000F) != 0 {
        stream_config.supported_video_formats & 0x000F
    } else {
        0x0001
    };
    let idr_flag = control_handle.as_ref().map(|c| c.idr_flag());
    let mut video_handle = match VideoStreamHandle::start(
        video_addr,
        negotiated_video_format,
        stream_config.width,
        stream_config.height,
        stream_config.fps,
        negotiated.video_ping_payload,
        idr_flag,
        Arc::clone(&stop_flag),
    ) {
        Ok(h) => h,
        Err(err) => {
            stop_flag.store(true, Ordering::SeqCst);
            if let Some(mut c) = control_handle.take() {
                c.stop();
            }
            jni_bridge::bridge_cl_stage_failed(STAGE_VIDEO_STREAM_START, err);
            return err;
        }
    };
    jni_bridge::bridge_cl_stage_complete(STAGE_VIDEO_STREAM_START);

    jni_bridge::bridge_cl_stage_starting(STAGE_AUDIO_STREAM_START);
    let audio_handle = match AudioStreamHandle::start(
        audio_addr,
        stream_config.audio_configuration,
        stream_config.ri_aes_key,
        stream_config.ri_aes_iv,
        (stream_config.encryption_flags & 0x05) != 0,
        negotiated.audio_ping_payload,
        Arc::clone(&stop_flag),
    ) {
        Ok(h) => h,
        Err(err) => {
            stop_flag.store(true, Ordering::SeqCst);
            video_handle.stop();
            if let Some(mut c) = control_handle.take() {
                c.stop();
            }
            jni_bridge::bridge_cl_stage_failed(STAGE_AUDIO_STREAM_START, err);
            return err;
        }
    };
    jni_bridge::bridge_cl_stage_complete(STAGE_AUDIO_STREAM_START);

    jni_bridge::bridge_cl_stage_starting(STAGE_INPUT_STREAM_START);
    let input_sock = UdpSocket::bind("0.0.0.0:0").ok();
    jni_bridge::bridge_cl_stage_complete(STAGE_INPUT_STREAM_START);

    let dispatcher = Arc::new(InputDispatcher {
        control: control_handle.as_ref().map(|c| c.clone_sender()),
        input_sock,
        control_addr,
        cipher: Aes128::new(&stream_config.ri_aes_key),
        ri_aes_iv: stream_config.ri_aes_iv,
        input_seq: AtomicU32::new(1),
        virtual_mouse_x: AtomicI16::new((stream_config.width / 2) as i16),
        virtual_mouse_y: AtomicI16::new((stream_config.height / 2) as i16),
    });

    if let Ok(mut dguard) = input_dispatch_lock().write() {
        *dguard = Some(dispatcher);
    }

    let active = ActiveSession {
        stop_flag,
        control_handle,
        rtt_ms: AtomicU32::new(5),
        rtt_var_ms: AtomicU32::new(1),
        video_handle: Some(video_handle),
        audio_handle: Some(audio_handle),
    };

    if let Ok(mut guard) = session_lock().lock() {
        *guard = Some(active);
    }

    send_raw_input(&input::serialize_mouse_move_rel(1, 1));
    send_raw_input(&input::serialize_mouse_move_rel(-1, -1));

    jni_bridge::bridge_cl_connection_started();
    0
}

pub fn interrupt_connection() {
    INTERRUPTED.store(true, Ordering::SeqCst);
}

pub fn stop_connection() {
    INTERRUPTED.store(true, Ordering::SeqCst);
    CONTROLLER_SPEAKER_OWNER.store(0, Ordering::Relaxed);
    if let Ok(mut dguard) = input_dispatch_lock().write() {
        *dguard = None;
    }
    if let Ok(mut guard) = session_lock().lock() {
        if let Some(mut session) = guard.take() {
            session.stop_flag.store(true, Ordering::SeqCst);
            if let Some(mut c) = session.control_handle.take() {
                c.stop();
            }
            if let Some(mut a) = session.audio_handle.take() {
                a.stop();
            }
            if let Some(mut v) = session.video_handle.take() {
                v.stop();
            }
        }
    }
}

pub fn send_raw_input(raw_packet: &[u8]) -> i32 {
    let dispatcher = match input_dispatch_lock().read() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(d) = dispatcher {
        return d.send_raw(raw_packet);
    }
    -1
}

pub fn send_mouse_move_as_position(
    delta_x: i16,
    delta_y: i16,
    ref_width: i16,
    ref_height: i16,
) -> i32 {
    let dispatcher = match input_dispatch_lock().read() {
        Ok(guard) => guard.as_ref().map(Arc::clone),
        Err(_) => None,
    };
    if let Some(d) = dispatcher {
        let prev_x = d.virtual_mouse_x.load(Ordering::Relaxed);
        let prev_y = d.virtual_mouse_y.load(Ordering::Relaxed);
        let next_x = prev_x.saturating_add(delta_x).clamp(0, ref_width.max(1));
        let next_y = prev_y.saturating_add(delta_y).clamp(0, ref_height.max(1));
        d.virtual_mouse_x.store(next_x, Ordering::Relaxed);
        d.virtual_mouse_y.store(next_y, Ordering::Relaxed);
        let pkt = input::serialize_mouse_move_abs(next_x, next_y, ref_width, ref_height);
        return d.send_raw(&pkt);
    }
    -1
}

pub fn get_pending_audio_duration() -> i32 {
    if let Ok(guard) = session_lock().lock() {
        if let Some(session) = guard.as_ref() {
            if let Some(a) = session.audio_handle.as_ref() {
                return a.pending_duration_ms();
            }
        }
    }
    0
}

pub fn get_pending_video_frames() -> i32 {
    if let Ok(guard) = session_lock().lock() {
        if let Some(session) = guard.as_ref() {
            if let Some(v) = session.video_handle.as_ref() {
                return v.pending_frames();
            }
        }
    }
    0
}

pub fn get_estimated_rtt_info() -> i64 {
    if let Ok(guard) = session_lock().lock() {
        if let Some(session) = guard.as_ref() {
            let rtt = session.rtt_ms.load(Ordering::Relaxed) as u64;
            let var = session.rtt_var_ms.load(Ordering::Relaxed) as u64;
            return ((rtt << 32) | var) as i64;
        }
    }
    -1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stage_names_and_port_flags() {
        assert_eq!(get_stage_name(STAGE_RTSP_HANDSHAKE), "RTSP handshake");
        assert_eq!(get_launch_url_query_parameters(), "&corever=1");
        let flags = get_port_flags_from_stage(STAGE_RTSP_HANDSHAKE);
        let text = stringify_port_flags(flags, ", ");
        assert_eq!(text, "TCP 48010, UDP 48000, UDP 48010");
    }
}
