//! Pure Rust ENet Control Stream Client for Vibemis.
//! Implements ENet UDP transport (port 47999), AES-128-GCM control packet encryption,
//! keepalives, ACKs, and high-frequency input forwarding.

use crate::jni_bridge;
use crate::protocol::crypto::Aes128;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const ENET_PROTOCOL_COMMAND_NONE: u8 = 0;
pub const ENET_PROTOCOL_COMMAND_ACKNOWLEDGE: u8 = 1;
pub const ENET_PROTOCOL_COMMAND_CONNECT: u8 = 2;
pub const ENET_PROTOCOL_COMMAND_VERIFY_CONNECT: u8 = 3;
pub const ENET_PROTOCOL_COMMAND_DISCONNECT: u8 = 4;
pub const ENET_PROTOCOL_COMMAND_PING: u8 = 5;
pub const ENET_PROTOCOL_COMMAND_SEND_RELIABLE: u8 = 6;
pub const ENET_PROTOCOL_COMMAND_SEND_UNSEQUENCED: u8 = 9;

pub const ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE: u8 = 0x80;
pub const ENET_PROTOCOL_COMMAND_FLAG_UNSEQUENCED: u8 = 0x40;
pub const ENET_PROTOCOL_COMMAND_MASK: u8 = 0x0F;

pub const CTRL_CHANNEL_GENERIC: u8 = 0x00;
pub const CTRL_CHANNEL_URGENT: u8 = 0x01;
pub const CTRL_CHANNEL_KEYBOARD: u8 = 0x02;
pub const CTRL_CHANNEL_MOUSE: u8 = 0x03;
pub const CTRL_CHANNEL_PEN: u8 = 0x04;
pub const CTRL_CHANNEL_TOUCH: u8 = 0x05;
pub const CTRL_CHANNEL_UTF8: u8 = 0x06;
pub const CTRL_CHANNEL_SERVERCTL: u8 = 0x08;
pub const CTRL_CHANNEL_GAMEPAD_BASE: u8 = 0x10;
pub const CTRL_CHANNEL_SENSOR_BASE: u8 = 0x20;
pub const CTRL_CHANNEL_COUNT: u32 = 0x30;

pub const PTYPE_INPUT_DATA: u16 = 0x0206;
pub const PTYPE_PERIODIC_PING: u16 = 0x0200;
pub const PTYPE_REQUEST_IDR_FRAME: u16 = 0x0302;
pub const PTYPE_RUMBLE_DATA: u16 = 0x010b;
pub const PTYPE_TERMINATION_LEGACY: u16 = 0x0100;
pub const PTYPE_TERMINATION_EXTENDED: u16 = 0x0109;
pub const PTYPE_HDR_MODE: u16 = 0x010e;
pub const PTYPE_RUMBLE_TRIGGERS: u16 = 0x5500;
pub const PTYPE_SET_MOTION_EVENT_STATE: u16 = 0x5501;
pub const PTYPE_SET_RGB_LED: u16 = 0x5502;
pub const PTYPE_DUALSENSE_ACTUATOR_PCM: u16 = 0x5601;
pub const PTYPE_CONTROLLER_SPEAKER_PCM: u16 = 0x5602;

#[derive(Clone)]
pub struct ControlSendHandle {
    sock: Arc<UdpSocket>,
    remote_addr: SocketAddr,
    cipher: Arc<Aes128>,
    remote_peer_id: Arc<AtomicU16>,
    session_id: Arc<AtomicU16>,
    input_seq: Arc<AtomicU32>,
    unseq_group: Arc<AtomicU16>,
    idr_requested: Arc<AtomicBool>,
}

impl ControlSendHandle {
    pub fn request_idr_frame(&self) {
        self.idr_requested.store(true, Ordering::Release);
    }

    pub fn send_input_packet(&self, raw_input: &[u8]) -> i32 {
        let seq = self.input_seq.fetch_add(1, Ordering::Relaxed);
        let unseq = self.unseq_group.fetch_add(1, Ordering::Relaxed);
        let peer_id = self.remote_peer_id.load(Ordering::Relaxed);
        let session = self.session_id.load(Ordering::Relaxed);
        let channel_id = classify_input_channel(raw_input);

        let enet_packet = build_enet_input_packet(
            peer_id,
            session,
            channel_id,
            unseq,
            seq,
            &self.cipher,
            raw_input,
        );

        match self.sock.send_to(&enet_packet, self.remote_addr) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    }
}

pub struct ControlStreamHandle {
    sock: Arc<UdpSocket>,
    remote_addr: SocketAddr,
    cipher: Arc<Aes128>,
    remote_peer_id: Arc<AtomicU16>,
    session_id: Arc<AtomicU16>,
    connected: Arc<AtomicBool>,
    input_seq: Arc<AtomicU32>,
    unseq_group: Arc<AtomicU16>,
    idr_requested: Arc<AtomicBool>,
    stop_flag: Arc<AtomicBool>,
    recv_thread: Option<JoinHandle<()>>,
}

impl ControlStreamHandle {
    pub fn start(
        remote_addr: SocketAddr,
        ri_aes_key: [u8; 16],
        connect_data: u32,
        stop_flag: Arc<AtomicBool>,
    ) -> Result<Self, i32> {
        let sock = UdpSocket::bind("0.0.0.0:0").map_err(|_| -1)?;
        let _ = sock.set_read_timeout(Some(Duration::from_millis(10)));
        let sock_arc = Arc::new(sock);

        let cipher = Arc::new(Aes128::new(&ri_aes_key));
        let remote_peer_id = Arc::new(AtomicU16::new(0x0FFF));
        let session_id = Arc::new(AtomicU16::new(0));
        let connected = Arc::new(AtomicBool::new(false));
        let input_seq = Arc::new(AtomicU32::new(1));
        let unseq_group = Arc::new(AtomicU16::new(1));
        let idr_requested = Arc::new(AtomicBool::new(false));

        let connect_pkt = build_enet_connect_packet(connect_data);
        let _ = sock_arc.send_to(&connect_pkt, remote_addr);

        let sock_clone = Arc::clone(&sock_arc);
        let stop_clone = Arc::clone(&stop_flag);
        let peer_clone = Arc::clone(&remote_peer_id);
        let sess_clone = Arc::clone(&session_id);
        let conn_clone = Arc::clone(&connected);
        let seq_clone = Arc::clone(&input_seq);
        let idr_clone = Arc::clone(&idr_requested);
        let cipher_clone = Arc::clone(&cipher);

        let handle = thread::Builder::new()
            .name("RustControlEnet".into())
            .spawn(move || {
                crate::cpu_affinity::pin_current_thread_to_big_cores();
                let mut buf = vec![0u8; 4096];
                let mut last_ping = Instant::now();
                let mut connect_retries = 0;
                let mut last_connect_send = Instant::now();
                let mut urgent_rel_seq = 1u16;

                while !stop_clone.load(Ordering::Relaxed) {
                    if !conn_clone.load(Ordering::Relaxed) {
                        if last_connect_send.elapsed() >= Duration::from_millis(500) && connect_retries < 5 {
                            connect_retries += 1;
                            last_connect_send = Instant::now();
                            let _ = sock_clone.send_to(&connect_pkt, remote_addr);
                        }
                    } else {
                        if idr_clone.swap(false, Ordering::AcqRel) {
                            let enc_seq = seq_clone.fetch_add(1, Ordering::Relaxed);
                            let rel_seq = urgent_rel_seq;
                            urgent_rel_seq = urgent_rel_seq.wrapping_add(1).max(1);
                            let idr_pkt = build_enet_reliable_control_packet(
                                peer_clone.load(Ordering::Relaxed),
                                sess_clone.load(Ordering::Relaxed),
                                CTRL_CHANNEL_URGENT,
                                rel_seq,
                                enc_seq,
                                &cipher_clone,
                                PTYPE_REQUEST_IDR_FRAME,
                                &[],
                            );
                            let _ = sock_clone.send_to(&idr_pkt, remote_addr);
                        }

                        if last_ping.elapsed() >= Duration::from_millis(1000) {
                            last_ping = Instant::now();
                            let ping_pkt = build_enet_ping_packet(
                                peer_clone.load(Ordering::Relaxed),
                                sess_clone.load(Ordering::Relaxed),
                            );
                            let _ = sock_clone.send_to(&ping_pkt, remote_addr);
                        }
                    }

                    if let Ok((len, from_addr)) = sock_clone.recv_from(&mut buf) {
                        if len < 2 || from_addr != remote_addr {
                            continue;
                        }

                        let peer_id_raw = u16::from_be_bytes([buf[0], buf[1]]);
                        let has_sent_time = (peer_id_raw & 0x8000) != 0;
                        let hdr_size = if has_sent_time { 4 } else { 2 };
                        if len < hdr_size {
                            continue;
                        }

                        let mut offset = hdr_size;
                        while offset + 4 <= len {
                            let _cmd_byte = buf[offset];
                            let cmd = _cmd_byte & ENET_PROTOCOL_COMMAND_MASK;
                            let needs_ack = (_cmd_byte & ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE) != 0;
                            let _channel_id = buf[offset + 1];
                            let rel_seq = u16::from_be_bytes([buf[offset + 2], buf[offset + 3]]);

                            match cmd {
                                ENET_PROTOCOL_COMMAND_VERIFY_CONNECT => {
                                    if offset + 44 <= len {
                                        let s_peer_id = u16::from_be_bytes([buf[offset + 4], buf[offset + 5]]);
                                        let incoming_sess = buf[offset + 6];
                                        peer_clone.store(s_peer_id, Ordering::Relaxed);
                                        sess_clone.store(incoming_sess as u16, Ordering::Relaxed);
                                        conn_clone.store(true, Ordering::Relaxed);

                                        let ack = build_enet_ack_packet(s_peer_id, incoming_sess as u16, rel_seq);
                                        let _ = sock_clone.send_to(&ack, remote_addr);
                                        offset += 44;
                                    } else {
                                        break;
                                    }
                                }
                                ENET_PROTOCOL_COMMAND_ACKNOWLEDGE => {
                                    conn_clone.store(true, Ordering::Relaxed);
                                    offset += 8;
                                }
                                ENET_PROTOCOL_COMMAND_PING => {
                                    if needs_ack {
                                        let ack = build_enet_ack_packet(
                                            peer_clone.load(Ordering::Relaxed),
                                            sess_clone.load(Ordering::Relaxed),
                                            rel_seq,
                                        );
                                        let _ = sock_clone.send_to(&ack, remote_addr);
                                    }
                                    offset += 4;
                                }
                                ENET_PROTOCOL_COMMAND_SEND_RELIABLE => {
                                    if offset + 6 <= len {
                                        let data_len = u16::from_be_bytes([buf[offset + 4], buf[offset + 5]]) as usize;
                                        let data_start = offset + 6;
                                        let data_end = data_start + data_len;
                                        if data_end <= len {
                                            handle_incoming_control_payload(
                                                &buf[data_start..data_end],
                                                &cipher_clone,
                                            );
                                            if needs_ack {
                                                let ack = build_enet_ack_packet(
                                                    peer_clone.load(Ordering::Relaxed),
                                                    sess_clone.load(Ordering::Relaxed),
                                                    rel_seq,
                                                );
                                                let _ = sock_clone.send_to(&ack, remote_addr);
                                            }
                                            offset = data_end;
                                        } else {
                                            break;
                                        }
                                    } else {
                                        break;
                                    }
                                }
                                ENET_PROTOCOL_COMMAND_SEND_UNSEQUENCED => {
                                    if offset + 8 <= len {
                                        let data_len = u16::from_be_bytes([buf[offset + 6], buf[offset + 7]]) as usize;
                                        let data_start = offset + 8;
                                        let data_end = data_start + data_len;
                                        if data_end <= len {
                                            handle_incoming_control_payload(
                                                &buf[data_start..data_end],
                                                &cipher_clone,
                                            );
                                            offset = data_end;
                                        } else {
                                            break;
                                        }
                                    } else {
                                        break;
                                    }
                                }
                                ENET_PROTOCOL_COMMAND_DISCONNECT => {
                                    jni_bridge::bridge_cl_connection_terminated(0);
                                    break;
                                }
                                _ => {
                                    // Advance or break on unknown command
                                    break;
                                }
                            }
                        }
                    }
                }
            })
            .map_err(|_| -1)?;

        Ok(Self {
            sock: sock_arc,
            remote_addr,
            cipher,
            remote_peer_id,
            session_id,
            connected,
            input_seq,
            unseq_group,
            idr_requested,
            stop_flag,
            recv_thread: Some(handle),
        })
    }

    pub fn clone_sender(&self) -> ControlSendHandle {
        ControlSendHandle {
            sock: Arc::clone(&self.sock),
            remote_addr: self.remote_addr,
            cipher: Arc::clone(&self.cipher),
            remote_peer_id: Arc::clone(&self.remote_peer_id),
            session_id: Arc::clone(&self.session_id),
            input_seq: Arc::clone(&self.input_seq),
            unseq_group: Arc::clone(&self.unseq_group),
            idr_requested: Arc::clone(&self.idr_requested),
        }
    }

    pub fn idr_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.idr_requested)
    }

    pub fn request_idr_frame(&self) {
        self.idr_requested.store(true, Ordering::Release);
    }

    pub fn send_input_packet(&self, raw_input: &[u8]) -> i32 {
        let seq = self.input_seq.fetch_add(1, Ordering::Relaxed);
        let unseq = self.unseq_group.fetch_add(1, Ordering::Relaxed);
        let peer_id = self.remote_peer_id.load(Ordering::Relaxed);
        let session = self.session_id.load(Ordering::Relaxed);
        let channel_id = classify_input_channel(raw_input);

        // Build ENet unsequenced datagram with AES-128-GCM encrypted payload on dedicated channel
        let enet_packet = build_enet_input_packet(
            peer_id,
            session,
            channel_id,
            unseq,
            seq,
            &self.cipher,
            raw_input,
        );

        match self.sock.send_to(&enet_packet, self.remote_addr) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Relaxed)
    }

    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        if let Some(h) = self.recv_thread.take() {
            let _ = h.join();
        }
    }
}

pub fn classify_input_channel(raw_input: &[u8]) -> u8 {
    if raw_input.len() < 8 {
        return CTRL_CHANNEL_GENERIC;
    }
    let magic = u32::from_le_bytes([raw_input[4], raw_input[5], raw_input[6], raw_input[7]]);
    match magic {
        // Keyboard: channel 0x02
        0x00000003 | 0x00000004 => CTRL_CHANNEL_KEYBOARD,
        // Mouse: channel 0x03
        0x00000005 | 0x00000007 | 0x00000008 | 0x00000009 | 0x0000000A | 0x55000001 => {
            CTRL_CHANNEL_MOUSE
        }
        // Pen: channel 0x04
        0x55000003 => CTRL_CHANNEL_PEN,
        // Touch: channel 0x05
        0x55000002 => CTRL_CHANNEL_TOUCH,
        // UTF-8 text: channel 0x06
        0x00000017 => CTRL_CHANNEL_UTF8,
        // Server command: channel 0x08
        0x80000000 => CTRL_CHANNEL_SERVERCTL,
        // Gamepad: channel 0x10 to 0x1F by controller index
        0x0000000C | 0x55000004 | 0x55000005 | 0x55000007 | 0x55000009 => {
            let controller_number = if raw_input.len() >= 12 {
                u16::from_le_bytes([raw_input[10], raw_input[11]]) as u8
            } else {
                0
            };
            CTRL_CHANNEL_GAMEPAD_BASE + (controller_number & 0x0F)
        }
        // Controller motion sensors: channel 0x20 to 0x2F
        0x55000006 => {
            let controller_number = if raw_input.len() >= 9 {
                raw_input[8]
            } else {
                0
            };
            CTRL_CHANNEL_SENSOR_BASE + (controller_number & 0x0F)
        }
        _ => CTRL_CHANNEL_GENERIC,
    }
}

pub fn build_enet_connect_packet(connect_data: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(50);
    // ENet header: peerID = 0x0FFF (ENET_PROTOCOL_MAXIMUM_PEER_ID, session 0, sentTime=0)
    out.extend_from_slice(&0x0FFFu16.to_be_bytes());

    // Command Header: CONNECT | ACK (0x82), channel 0xFF, rel seq = 1
    out.push(ENET_PROTOCOL_COMMAND_CONNECT | ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE);
    out.push(0xFF);
    out.extend_from_slice(&1u16.to_be_bytes());

    // Connect body (44 bytes)
    out.extend_from_slice(&0u16.to_be_bytes()); // outgoingPeerID
    out.push(0xFF); // incomingSessionID
    out.push(0xFF); // outgoingSessionID
    out.extend_from_slice(&1400u32.to_be_bytes()); // mtu
    out.extend_from_slice(&4096u32.to_be_bytes()); // windowSize
    out.extend_from_slice(&CTRL_CHANNEL_COUNT.to_be_bytes()); // channelCount = 0x30 (48 channels)
    out.extend_from_slice(&0u32.to_be_bytes()); // incomingBandwidth
    out.extend_from_slice(&0u32.to_be_bytes()); // outgoingBandwidth
    out.extend_from_slice(&5000u32.to_be_bytes()); // packetThrottleInterval
    out.extend_from_slice(&2u32.to_be_bytes()); // packetThrottleAcceleration
    out.extend_from_slice(&2u32.to_be_bytes()); // packetThrottleDeceleration
    out.extend_from_slice(&0x56494245u32.to_be_bytes()); // connectID ('VIBE')
    out.extend_from_slice(&connect_data.to_be_bytes()); // data (connect data from RTSP)

    out
}

pub fn build_enet_ack_packet(peer_id: u16, session_id: u16, received_rel_seq: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(12);
    let enet_peer_hdr = (peer_id & 0x0FFF) | ((session_id & 0x03) << 12);
    out.extend_from_slice(&enet_peer_hdr.to_be_bytes());

    // Command: ACK (1), channel 0xFF, rel seq = 0
    out.push(ENET_PROTOCOL_COMMAND_ACKNOWLEDGE);
    out.push(0xFF);
    out.extend_from_slice(&0u16.to_be_bytes());

    // Body: received seq + sent time
    out.extend_from_slice(&received_rel_seq.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes()); // receivedSentTime
    out
}

pub fn build_enet_ping_packet(peer_id: u16, session_id: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(6);
    let enet_peer_hdr = (peer_id & 0x0FFF) | ((session_id & 0x03) << 12);
    out.extend_from_slice(&enet_peer_hdr.to_be_bytes());

    // Command: PING (5), channel 0xFF, rel seq = 0
    out.push(ENET_PROTOCOL_COMMAND_PING);
    out.push(0xFF);
    out.extend_from_slice(&0u16.to_be_bytes());
    out
}

pub fn build_enet_reliable_control_packet(
    peer_id: u16,
    session_id: u16,
    channel_id: u8,
    rel_seq: u16,
    enc_seq: u32,
    cipher: &Aes128,
    ptype: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut plaintext = Vec::with_capacity(4 + payload.len());
    plaintext.extend_from_slice(&ptype.to_le_bytes());
    plaintext.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    plaintext.extend_from_slice(payload);

    let mut iv = [0u8; 12];
    iv[0..4].copy_from_slice(&enc_seq.to_le_bytes());
    iv[10] = b'C';
    iv[11] = b'C';

    let (ciphertext, tag) = cipher.encrypt_gcm(&iv, &[], &plaintext);

    let enc_body_len = 4 + 16 + plaintext.len();
    let mut enc_packet = Vec::with_capacity(8 + 16 + ciphertext.len());
    enc_packet.extend_from_slice(&1u16.to_le_bytes());
    enc_packet.extend_from_slice(&(enc_body_len as u16).to_le_bytes());
    enc_packet.extend_from_slice(&enc_seq.to_le_bytes());
    enc_packet.extend_from_slice(&tag);
    enc_packet.extend_from_slice(&ciphertext);

    let enet_peer_hdr = (peer_id & 0x0FFF) | ((session_id & 0x03) << 12);
    let mut out = Vec::with_capacity(2 + 6 + enc_packet.len());
    out.extend_from_slice(&enet_peer_hdr.to_be_bytes());
    out.push(ENET_PROTOCOL_COMMAND_SEND_RELIABLE | ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE);
    out.push(channel_id);
    out.extend_from_slice(&rel_seq.to_be_bytes());
    out.extend_from_slice(&(enc_packet.len() as u16).to_be_bytes());
    out.extend_from_slice(&enc_packet);

    out
}

pub fn build_enet_input_packet(
    peer_id: u16,
    session_id: u16,
    channel_id: u8,
    unseq_group: u16,
    seq: u32,
    cipher: &Aes128,
    raw_input: &[u8],
) -> Vec<u8> {
    // 1. Plaintext = NVCTL_ENET_PACKET_HEADER_V2 (type 0x0206 LE + length LE) + raw_input
    let mut plaintext = Vec::with_capacity(4 + raw_input.len());
    plaintext.extend_from_slice(&PTYPE_INPUT_DATA.to_le_bytes());
    plaintext.extend_from_slice(&(raw_input.len() as u16).to_le_bytes());
    plaintext.extend_from_slice(raw_input);

    // 2. AES-128-GCM encryption with 12-byte IV
    let mut iv = [0u8; 12];
    iv[0..4].copy_from_slice(&seq.to_le_bytes());
    iv[10] = b'C'; // Client originated
    iv[11] = b'C'; // Control stream

    let (ciphertext, tag) = cipher.encrypt_gcm(&iv, &[], &plaintext);

    // 3. NVCTL_ENCRYPTED_PACKET_HEADER: type 0x0001 (LE), length (LE), seq (LE) + tag + ciphertext
    let enc_body_len = 4 + 16 + plaintext.len(); // seq (4) + tag (16) + data
    let mut enc_packet = Vec::with_capacity(8 + 16 + ciphertext.len());
    enc_packet.extend_from_slice(&1u16.to_le_bytes()); // type 0x0001
    enc_packet.extend_from_slice(&(enc_body_len as u16).to_le_bytes());
    enc_packet.extend_from_slice(&seq.to_le_bytes());
    enc_packet.extend_from_slice(&tag);
    enc_packet.extend_from_slice(&ciphertext);

    // 4. ENet SEND_UNSEQUENCED datagram on the specific input channel
    let enet_peer_hdr = (peer_id & 0x0FFF) | ((session_id & 0x03) << 12);
    let mut out = Vec::with_capacity(2 + 8 + enc_packet.len());
    out.extend_from_slice(&enet_peer_hdr.to_be_bytes());

    // Command Header: SEND_UNSEQUENCED | FLAG_UNSEQUENCED (0x49), channel, rel seq 0
    out.push(ENET_PROTOCOL_COMMAND_SEND_UNSEQUENCED | ENET_PROTOCOL_COMMAND_FLAG_UNSEQUENCED);
    out.push(channel_id);
    out.extend_from_slice(&0u16.to_be_bytes()); // reliable seq 0

    // Command Body: unseq group (BE) + data length (BE)
    out.extend_from_slice(&unseq_group.to_be_bytes());
    out.extend_from_slice(&(enc_packet.len() as u16).to_be_bytes());
    out.extend_from_slice(&enc_packet);

    out
}

fn handle_incoming_control_payload(payload: &[u8], cipher: &Aes128) {
    if payload.len() < 8 {
        return;
    }

    let enc_type = u16::from_le_bytes([payload[0], payload[1]]);
    if enc_type == 1 && payload.len() >= 24 {
        let _length = u16::from_le_bytes([payload[2], payload[3]]) as usize;
        let seq = u32::from_le_bytes([payload[4], payload[5], payload[6], payload[7]]);
        let mut tag = [0u8; 16];
        tag.copy_from_slice(&payload[8..24]);
        let ciphertext = &payload[24..];

        let mut iv = [0u8; 12];
        iv[0..4].copy_from_slice(&seq.to_le_bytes());
        iv[10] = b'H'; // Host originated
        iv[11] = b'C'; // Control stream

        if let Some(decrypted) = cipher.decrypt_gcm(&iv, &[], ciphertext, &tag) {
            if decrypted.len() >= 4 {
                let ptype = u16::from_le_bytes([decrypted[0], decrypted[1]]);
                let msg_data = &decrypted[4..];
                dispatch_control_message(ptype, msg_data);
            }
        }
    } else {
        // Plaintext V1 or unencrypted
        let ptype = u16::from_le_bytes([payload[0], payload[1]]);
        let msg_data = &payload[2..];
        dispatch_control_message(ptype, msg_data);
    }
}

fn dispatch_control_message(ptype: u16, data: &[u8]) {
    match ptype {
        PTYPE_RUMBLE_DATA => {
            if data.len() >= 10 {
                let controller = u16::from_le_bytes([data[4], data[5]]);
                let low = u16::from_le_bytes([data[6], data[7]]);
                let high = u16::from_le_bytes([data[8], data[9]]);
                jni_bridge::bridge_cl_rumble(controller as i16, low as i16, high as i16);
            } else if data.len() >= 6 {
                let controller = u16::from_le_bytes([data[0], data[1]]);
                let low = u16::from_le_bytes([data[2], data[3]]);
                let high = u16::from_le_bytes([data[4], data[5]]);
                jni_bridge::bridge_cl_rumble(controller as i16, low as i16, high as i16);
            }
        }
        PTYPE_RUMBLE_TRIGGERS => {
            if data.len() >= 6 {
                let controller = u16::from_le_bytes([data[0], data[1]]);
                let left = u16::from_le_bytes([data[2], data[3]]);
                let right = u16::from_le_bytes([data[4], data[5]]);
                jni_bridge::bridge_cl_rumble_triggers(controller as i16, left as i16, right as i16);
            }
        }
        PTYPE_SET_MOTION_EVENT_STATE => {
            if data.len() >= 5 {
                let controller = u16::from_le_bytes([data[0], data[1]]);
                let report_rate = u16::from_le_bytes([data[2], data[3]]);
                let motion_type = data[4];
                jni_bridge::bridge_cl_set_motion_event_state(
                    controller as i16,
                    motion_type,
                    report_rate as i16,
                );
            }
        }
        PTYPE_SET_RGB_LED => {
            if data.len() >= 5 {
                let controller = u16::from_le_bytes([data[0], data[1]]);
                jni_bridge::bridge_cl_set_controller_led(
                    controller as i16,
                    data[2],
                    data[3],
                    data[4],
                );
            }
        }
        PTYPE_HDR_MODE => {
            if !data.is_empty() {
                let enabled = data[0] != 0;
                let metadata = if data.len() >= 25 {
                    Some(&data[1..25])
                } else {
                    None
                };
                jni_bridge::bridge_cl_set_hdr_mode(enabled, metadata);
            }
        }
        PTYPE_CONTROLLER_SPEAKER_PCM => {
            if data.len() >= 992 && data[0] == 2 && data[1] == 1 {
                let controller = u16::from_le_bytes([data[2], data[3]]) as i16;
                let frame_count = u16::from_le_bytes([data[20], data[21]]);
                let client_owner = u64::from_le_bytes([
                    data[24], data[25], data[26], data[27], data[28], data[29], data[30], data[31],
                ]);
                let my_owner = crate::protocol::get_controller_speaker_owner();
                if frame_count == 240 && (my_owner == 0 || client_owner == 0 || client_owner == my_owner) {
                    jni_bridge::bridge_cl_controller_speaker_pcm(controller, &data[32..992]);
                }
            }
        }
        PTYPE_TERMINATION_LEGACY | PTYPE_TERMINATION_EXTENDED => {
            let error_code = if data.len() >= 4 {
                i32::from_be_bytes([data[0], data[1], data[2], data[3]])
            } else {
                0
            };
            jni_bridge::bridge_cl_connection_terminated(error_code);
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_enet_connect_packet_structure() {
        let pkt = build_enet_connect_packet(0x12345678);
        assert_eq!(pkt.len(), 50);
        assert_eq!(&pkt[0..2], &0x0FFFu16.to_be_bytes()); // PeerID ENET_PROTOCOL_MAXIMUM_PEER_ID
        assert_eq!(pkt[2], ENET_PROTOCOL_COMMAND_CONNECT | ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE);
        assert_eq!(pkt[3], 0xFF); // Channel
        assert_eq!(&pkt[4..6], &1u16.to_be_bytes()); // Seq 1
        assert_eq!(&pkt[6..8], &0u16.to_be_bytes()); // outgoingPeerID
        assert_eq!(&pkt[10..14], &1400u32.to_be_bytes()); // MTU
        assert_eq!(&pkt[18..22], &48u32.to_be_bytes()); // ChannelCount 48 (0x30)
        assert_eq!(&pkt[42..46], &0x56494245u32.to_be_bytes()); // ConnectID 'VIBE'
        assert_eq!(&pkt[46..50], &0x12345678u32.to_be_bytes()); // ConnectData
    }

    #[test]
    fn test_enet_ack_packet_structure() {
        let ack = build_enet_ack_packet(42, 1, 15);
        assert_eq!(ack.len(), 10);
        let expected_peer: u16 = (42 & 0x0FFF) | (1 << 12);
        assert_eq!(&ack[0..2], &expected_peer.to_be_bytes());
        assert_eq!(ack[2], ENET_PROTOCOL_COMMAND_ACKNOWLEDGE);
        assert_eq!(&ack[4..6], &0u16.to_be_bytes()); // reliableSeq 0
        assert_eq!(&ack[6..8], &15u16.to_be_bytes()); // Received reliable sequence
    }

    #[test]
    fn test_classify_input_channel() {
        // Mouse move rel
        let mouse = crate::protocol::input::serialize_mouse_move_rel(10, 20);
        assert_eq!(classify_input_channel(&mouse), CTRL_CHANNEL_MOUSE);

        // Keyboard event
        let kb = crate::protocol::input::serialize_keyboard_event(65, 3, 0, 0);
        assert_eq!(classify_input_channel(&kb), CTRL_CHANNEL_KEYBOARD);

        // Gamepad 0
        let pad0 = crate::protocol::input::serialize_multi_controller(0, 1, 0, 0, 0, 0, 0, 0, 0);
        assert_eq!(classify_input_channel(&pad0), CTRL_CHANNEL_GAMEPAD_BASE);

        // Gamepad 1
        let pad1 = crate::protocol::input::serialize_multi_controller(1, 2, 0, 0, 0, 0, 0, 0, 0);
        assert_eq!(classify_input_channel(&pad1), CTRL_CHANNEL_GAMEPAD_BASE + 1);
    }

    #[test]
    fn test_enet_input_packet_encryption_and_decryption() {
        let key = [0x55u8; 16];
        let cipher = Aes128::new(&key);
        let raw_mouse = vec![0x00, 0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04];

        let enet_pkt = build_enet_input_packet(5, 0, CTRL_CHANNEL_MOUSE, 100, 1, &cipher, &raw_mouse);
        assert!(enet_pkt.len() > 30);

        // Verify ENet header and command
        assert_eq!(&enet_pkt[0..2], &5u16.to_be_bytes()); // peer ID 5
        assert_eq!(enet_pkt[2], ENET_PROTOCOL_COMMAND_SEND_UNSEQUENCED | ENET_PROTOCOL_COMMAND_FLAG_UNSEQUENCED);
        assert_eq!(enet_pkt[3], CTRL_CHANNEL_MOUSE); // Channel 0x03

        // Extract encrypted payload inside ENet command:
        let enc_payload = &enet_pkt[10..];
        let enc_type = u16::from_le_bytes([enc_payload[0], enc_payload[1]]);
        assert_eq!(enc_type, 1); // 0x0001 (NVCTL_ENCRYPTED_PACKET_HEADER)
        let seq = u32::from_le_bytes([enc_payload[4], enc_payload[5], enc_payload[6], enc_payload[7]]);
        assert_eq!(seq, 1);

        let mut tag = [0u8; 16];
        tag.copy_from_slice(&enc_payload[8..24]);
        let ciphertext = &enc_payload[24..];

        let mut iv = [0u8; 12];
        iv[0..4].copy_from_slice(&seq.to_le_bytes());
        iv[10] = b'C';
        iv[11] = b'C';

        let decrypted = cipher.decrypt_gcm(&iv, &[], ciphertext, &tag).expect("Decryption must succeed");
        assert_eq!(u16::from_le_bytes([decrypted[0], decrypted[1]]), PTYPE_INPUT_DATA);
        assert_eq!(u16::from_le_bytes([decrypted[2], decrypted[3]]), raw_mouse.len() as u16);
        assert_eq!(&decrypted[4..], &raw_mouse);
    }

    #[test]
    fn test_enet_reliable_idr_request_packet() {
        let key = [0x42u8; 16];
        let cipher = Aes128::new(&key);
        let pkt = build_enet_reliable_control_packet(
            7,
            2,
            CTRL_CHANNEL_URGENT,
            3,
            11,
            &cipher,
            PTYPE_REQUEST_IDR_FRAME,
            &[],
        );
        let expected_peer: u16 = (7 & 0x0FFF) | (2 << 12);
        assert_eq!(&pkt[0..2], &expected_peer.to_be_bytes());
        assert_eq!(pkt[2], ENET_PROTOCOL_COMMAND_SEND_RELIABLE | ENET_PROTOCOL_COMMAND_FLAG_ACKNOWLEDGE);
        assert_eq!(pkt[3], CTRL_CHANNEL_URGENT);
        assert_eq!(&pkt[4..6], &3u16.to_be_bytes());

        let enc_payload = &pkt[8..];
        let seq = u32::from_le_bytes([enc_payload[4], enc_payload[5], enc_payload[6], enc_payload[7]]);
        assert_eq!(seq, 11);
        let mut tag = [0u8; 16];
        tag.copy_from_slice(&enc_payload[8..24]);
        let mut iv = [0u8; 12];
        iv[0..4].copy_from_slice(&seq.to_le_bytes());
        iv[10] = b'C';
        iv[11] = b'C';
        let decrypted = cipher.decrypt_gcm(&iv, &[], &enc_payload[24..], &tag).unwrap();
        assert_eq!(u16::from_le_bytes([decrypted[0], decrypted[1]]), PTYPE_REQUEST_IDR_FRAME);
        assert_eq!(u16::from_le_bytes([decrypted[2], decrypted[3]]), 0);
    }
}
