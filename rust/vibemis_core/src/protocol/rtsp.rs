//! Pure Rust RTSP Parser, Serializer, and Handshake Client.
//! Pure Rust RTSP 1.0 handshake and framing client for Vibemis.

use crate::protocol::crypto::Aes128;
use crate::protocol::sdp::generate_sdp;
use crate::protocol::{ServerInfo, StreamConfig};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct RtspResponse {
    pub status_code: u16,
    pub status_text: String,
    pub headers: HashMap<String, String>,
    pub body: String,
}

#[derive(Debug, Clone)]
pub struct NegotiatedSession {
    pub session_id: String,
    pub video_port: u16,
    pub audio_port: u16,
    pub control_port: u16,
    pub video_ping_payload: Option<[u8; 16]>,
    pub audio_ping_payload: Option<[u8; 16]>,
    pub control_connect_data: u32,
    pub sunshine_feature_flags: u32,
    pub negotiated_pyrowave: bool,
    pub ref_pic_invalidation: bool,
}

pub fn build_rtsp_request(
    method: &str,
    target: &str,
    cseq: u32,
    session_id: Option<&str>,
    extra_headers: &[(&str, &str)],
    payload: Option<&str>,
) -> String {
    let mut req = format!("{method} {target} RTSP/1.0\r\nCSeq: {cseq}\r\n");
    req.push_str("X-GS-ClientVersion: 14\r\n");
    if let Some(sid) = session_id {
        req.push_str(&format!("Session: {sid}\r\n"));
    }
    for (k, v) in extra_headers {
        req.push_str(&format!("{k}: {v}\r\n"));
    }
    if let Some(body) = payload {
        req.push_str("Content-Type: application/sdp\r\n");
        req.push_str(&format!("Content-Length: {}\r\n\r\n{}", body.len(), body));
    } else {
        req.push_str("\r\n");
    }
    req
}

pub fn parse_rtsp_response(raw: &str) -> Option<RtspResponse> {
    let (head, body) = raw.split_once("\r\n\r\n")?;
    let mut lines = head.lines();
    let status_line = lines.next()?;
    let mut parts = status_line.splitn(3, ' ');
    let _proto = parts.next()?;
    let status_code: u16 = parts.next()?.parse().ok()?;
    let status_text = parts.next().unwrap_or("").to_string();

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }

    Some(RtspResponse {
        status_code,
        status_text,
        headers,
        body: body.to_string(),
    })
}

pub fn encrypt_rtsp_message(cipher: &Aes128, seq: u32, plaintext: &[u8]) -> Vec<u8> {
    let mut iv = [0u8; 12];
    iv[0..4].copy_from_slice(&seq.to_le_bytes());
    iv[10] = b'C';
    iv[11] = b'R';

    let (ct, tag) = cipher.encrypt_gcm(&iv, &[], plaintext);
    let header_len = (0x80000000u32 | (plaintext.len() as u32)).to_be_bytes();
    let mut out = Vec::with_capacity(4 + 4 + 16 + ct.len());
    out.extend_from_slice(&header_len);
    out.extend_from_slice(&seq.to_be_bytes());
    out.extend_from_slice(&tag);
    out.extend_from_slice(&ct);
    out
}

pub fn decrypt_rtsp_message(cipher: &Aes128, packet: &[u8]) -> Option<Vec<u8>> {
    if packet.len() < 24 {
        return None;
    }
    let header = u32::from_be_bytes([packet[0], packet[1], packet[2], packet[3]]);
    if (header & 0x80000000) == 0 {
        return None;
    }
    let msg_len = (header & 0x7FFFFFFF) as usize;
    let seq = u32::from_be_bytes([packet[4], packet[5], packet[6], packet[7]]);
    let mut tag = [0u8; 16];
    tag.copy_from_slice(&packet[8..24]);
    let ct = &packet[24..];
    if ct.len() < msg_len {
        return None;
    }

    let mut iv = [0u8; 12];
    iv[0..4].copy_from_slice(&seq.to_le_bytes());
    iv[10] = b'H';
    iv[11] = b'R';

    cipher.decrypt_gcm(&iv, &[], &ct[..msg_len], &tag)
}

fn parse_port_from_transport(transport: &str, default_port: u16) -> u16 {
    for part in transport.split(';') {
        let trimmed = part.trim();
        if let Some(val) = trimmed.strip_prefix("server_port=") {
            let port_part = val.split('-').next().unwrap_or(val).trim();
            if let Ok(p) = port_part.parse::<u16>() {
                return p;
            }
        }
    }
    default_port
}

fn parse_ping_payload(val: Option<&String>) -> Option<[u8; 16]> {
    let s = val?;
    if s.len() >= 16 {
        let mut out = [0u8; 16];
        out.copy_from_slice(&s.as_bytes()[..16]);
        Some(out)
    } else {
        None
    }
}

const MAX_RTSP_BODY_BYTES: usize = 1024 * 1024;
const MAX_RTSP_HEADER_BYTES: usize = 32 * 1024;

fn find_header_end(buf: &[u8]) -> Option<(usize, usize)> {
    for i in 0..buf.len() {
        if i + 4 <= buf.len() && &buf[i..i + 4] == b"\r\n\r\n" {
            return Some((i, 4));
        }
        if i + 2 <= buf.len() && &buf[i..i + 2] == b"\n\n" {
            return Some((i, 2));
        }
    }
    None
}

fn parse_content_length(header_bytes: &[u8]) -> usize {
    let text = String::from_utf8_lossy(header_bytes);
    for line in text.lines().skip(1) {
        if let Some((k, v)) = line.split_once(':') {
            if k.trim().eq_ignore_ascii_case("content-length") {
                return v.trim().parse::<usize>().unwrap_or(0).min(MAX_RTSP_BODY_BYTES);
            }
        }
    }
    0
}

pub fn read_plain_rtsp_response<R: Read>(reader: &mut R) -> Result<RtspResponse, i32> {
    let mut buf = Vec::with_capacity(1024);
    let mut byte = [0u8; 1];
    let (header_end, delim_len) = loop {
        if buf.len() >= MAX_RTSP_HEADER_BYTES {
            return Err(-5);
        }
        match reader.read(&mut byte) {
            Ok(0) => {
                if buf.is_empty() {
                    return Err(-4);
                }
                break (buf.len(), 0);
            }
            Ok(_) => {
                buf.push(byte[0]);
                if let Some(found) = find_header_end(&buf) {
                    break found;
                }
            }
            Err(_) => return Err(-4),
        }
    };

    let content_length = parse_content_length(&buf[..header_end]);
    let already_have = buf.len().saturating_sub(header_end + delim_len);
    if content_length > already_have {
        let remaining = content_length - already_have;
        let offset = buf.len();
        buf.resize(offset + remaining, 0);
        reader.read_exact(&mut buf[offset..]).map_err(|_| -4)?;
    }

    let text = String::from_utf8_lossy(&buf);
    parse_rtsp_response(&text).ok_or(-5)
}

pub fn read_encrypted_rtsp_response<R: Read>(
    reader: &mut R,
    cipher: &Aes128,
) -> Result<RtspResponse, i32> {
    let mut hdr = [0u8; 4];
    reader.read_exact(&mut hdr).map_err(|_| -4)?;
    let header = u32::from_be_bytes(hdr);
    if (header & 0x8000_0000) == 0 {
        return Err(-4);
    }
    let msg_len = (header & 0x7FFF_FFFF) as usize;
    if msg_len > MAX_RTSP_BODY_BYTES {
        return Err(-4);
    }
    let mut packet = Vec::with_capacity(4 + 4 + 16 + msg_len);
    packet.extend_from_slice(&hdr);
    packet.resize(24 + msg_len, 0);
    reader.read_exact(&mut packet[4..]).map_err(|_| -4)?;
    let decrypted = decrypt_rtsp_message(cipher, &packet).ok_or(-4)?;
    let text = String::from_utf8_lossy(&decrypted);
    parse_rtsp_response(&text).ok_or(-5)
}

fn open_rtsp_stream(addr: SocketAddr) -> Result<TcpStream, i32> {
    let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(10)).map_err(|_| -2)?;
    let _ = stream.set_nodelay(true);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
    Ok(stream)
}

pub fn perform_rtsp_handshake(
    server_info: &ServerInfo,
    stream_config: &StreamConfig,
    rtsp_port: u16,
) -> Result<NegotiatedSession, i32> {
    let addr: SocketAddr = (server_info.address.as_str(), rtsp_port)
        .to_socket_addrs()
        .map_err(|_| -1)?
        .next()
        .ok_or(-1)?;

    let encrypted = server_info
        .rtsp_session_url
        .as_deref()
        .map(|u| u.starts_with("rtspenc://"))
        .unwrap_or(false);
    let cipher = Aes128::new(&stream_config.ri_aes_key);
    let mut enc_seq = 1u32;

    let target_url = server_info
        .rtsp_session_url
        .clone()
        .unwrap_or_else(|| format!("rtsp://{}:{rtsp_port}", server_info.address));
    let host_header = server_info.address.as_str();

    let mut persistent_stream: Option<TcpStream> = Some(open_rtsp_stream(addr)?);

    let mut send_rtsp = |req_str: &str, seq: &mut u32| -> Result<RtspResponse, i32> {
        let current_seq = *seq;
        let wire = if encrypted {
            *seq = seq.wrapping_add(1);
            encrypt_rtsp_message(&cipher, current_seq, req_str.as_bytes())
        } else {
            Vec::new()
        };

        for attempt in 0..2 {
            if persistent_stream.is_none() {
                persistent_stream = Some(open_rtsp_stream(addr)?);
            }
            let stream = persistent_stream.as_mut().expect("stream initialized");
            let write_res = if encrypted {
                stream.write_all(&wire)
            } else {
                stream.write_all(req_str.as_bytes())
            };
            if write_res.is_err() {
                persistent_stream = None;
                if attempt == 0 {
                    continue;
                }
                return Err(-3);
            }
            let _ = stream.flush();

            let resp_res = if encrypted {
                read_encrypted_rtsp_response(stream, &cipher)
            } else {
                read_plain_rtsp_response(stream)
            };

            match resp_res {
                Ok(resp) => {
                    if resp
                        .headers
                        .get("connection")
                        .map(|v| v.eq_ignore_ascii_case("close"))
                        .unwrap_or(false)
                    {
                        persistent_stream = None;
                    }
                    return Ok(resp);
                }
                Err(code) => {
                    persistent_stream = None;
                    if attempt == 0 && !encrypted {
                        continue;
                    }
                    return Err(code);
                }
            }
        }
        Err(-4)
    };

    let mut cseq = 1u32;
    let opt_req = build_rtsp_request(
        "OPTIONS",
        &target_url,
        cseq,
        None,
        &[("Host", host_header)],
        None,
    );
    let opt_resp = send_rtsp(&opt_req, &mut enc_seq)?;
    if opt_resp.status_code != 200 {
        return Err(-(opt_resp.status_code as i32));
    }
    cseq += 1;

    let desc_req = build_rtsp_request(
        "DESCRIBE",
        &target_url,
        cseq,
        None,
        &[
            ("Host", host_header),
            ("Accept", "application/sdp"),
            ("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
        ],
        None,
    );
    let desc_resp = send_rtsp(&desc_req, &mut enc_seq)?;
    if desc_resp.status_code != 200 {
        return Err(-(desc_resp.status_code as i32));
    }
    cseq += 1;

    let parse_sdp_uint = |body: &str, attr: &str| -> u32 {
        for line in body.lines() {
            if let Some(rest) = line.trim().strip_prefix("a=") {
                if let Some(val) = rest.strip_prefix(attr).and_then(|s| s.strip_prefix(':')) {
                    if let Ok(parsed) = val.trim().parse::<u32>() {
                        return parsed;
                    }
                }
            }
        }
        0
    };
    let enc_supported = parse_sdp_uint(&desc_resp.body, "x-ss-general.encryptionSupported");
    let enc_requested = parse_sdp_uint(&desc_resp.body, "x-ss-general.encryptionRequested");
    let negotiated_pyrowave = parse_sdp_uint(&desc_resp.body, "x-ss-pyrowave.bitstream") != 0
        || parse_sdp_uint(&desc_resp.body, "x-ss-video[0].pyrowaveFeatures") != 0;
    let ref_pic_invalidation = parse_sdp_uint(&desc_resp.body, "x-nv-video[0].refPicInvalidation") != 0;
    let sunshine_feature_flags = {
        let f = parse_sdp_uint(&desc_resp.body, "x-ss-general.featureFlags");
        if f != 0 { f } else { 0x03 }
    };
    let mut negotiated_enc_flags = (stream_config.encryption_flags as u32) & enc_supported;
    if (enc_supported & 0x01) != 0 {
        negotiated_enc_flags |= 0x01; // SS_ENC_CONTROL_V2
    }
    negotiated_enc_flags |= enc_requested;

    let setup_audio = build_rtsp_request(
        "SETUP",
        "streamid=audio/0/0",
        cseq,
        None,
        &[
            ("Host", host_header),
            ("Transport", "unicast;X-GS-ClientPort=50000-50001"),
            ("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
        ],
        None,
    );
    let audio_resp = send_rtsp(&setup_audio, &mut enc_seq)?;
    if audio_resp.status_code != 200 {
        return Err(-(audio_resp.status_code as i32));
    }
    cseq += 1;

    let session_id = audio_resp
        .headers
        .get("session")
        .map(|s| s.split(';').next().unwrap_or(s).trim().to_string())
        .unwrap_or_else(|| "DEADBEEFCAFE".to_string());
    let audio_port = audio_resp
        .headers
        .get("transport")
        .map(|t| parse_port_from_transport(t, 48000))
        .unwrap_or(48000);
    let audio_ping_payload = parse_ping_payload(audio_resp.headers.get("x-ss-ping-payload"));

    let setup_video = build_rtsp_request(
        "SETUP",
        "streamid=video/0/0",
        cseq,
        Some(&session_id),
        &[
            ("Host", host_header),
            ("Transport", "unicast;X-GS-ClientPort=50000-50001"),
            ("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
        ],
        None,
    );
    let video_resp = send_rtsp(&setup_video, &mut enc_seq)?;
    if video_resp.status_code != 200 {
        return Err(-(video_resp.status_code as i32));
    }
    cseq += 1;

    let video_port = video_resp
        .headers
        .get("transport")
        .map(|t| parse_port_from_transport(t, 47998))
        .unwrap_or(47998);
    let video_ping_payload = parse_ping_payload(video_resp.headers.get("x-ss-ping-payload"));

    let setup_ctrl = build_rtsp_request(
        "SETUP",
        "streamid=control/13/0",
        cseq,
        Some(&session_id),
        &[
            ("Host", host_header),
            ("Transport", "unicast;X-GS-ClientPort=50000-50001"),
            ("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT"),
        ],
        None,
    );
    let ctrl_resp = send_rtsp(&setup_ctrl, &mut enc_seq)?;
    if ctrl_resp.status_code != 200 {
        return Err(-(ctrl_resp.status_code as i32));
    }
    cseq += 1;

    let control_port = ctrl_resp
        .headers
        .get("transport")
        .map(|t| parse_port_from_transport(t, 47999))
        .unwrap_or(47999);
    let control_connect_data = ctrl_resp
        .headers
        .get("x-ss-connect-data")
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);

    let sdp = generate_sdp(
        stream_config,
        &server_info.address,
        14,
        true,
        negotiated_enc_flags,
    );
    let announce_req = build_rtsp_request(
        "ANNOUNCE",
        "streamid=control/13/0",
        cseq,
        Some(&session_id),
        &[("Host", host_header)],
        Some(&sdp),
    );
    let announce_resp = send_rtsp(&announce_req, &mut enc_seq)?;
    if announce_resp.status_code != 200 {
        return Err(-(announce_resp.status_code as i32));
    }
    cseq += 1;

    let play_req = build_rtsp_request(
        "PLAY",
        "/",
        cseq,
        Some(&session_id),
        &[("Host", host_header)],
        None,
    );
    let play_resp = send_rtsp(&play_req, &mut enc_seq)?;
    if play_resp.status_code != 200 {
        return Err(-(play_resp.status_code as i32));
    }

    Ok(NegotiatedSession {
        session_id,
        video_port,
        audio_port,
        control_port,
        video_ping_payload,
        audio_ping_payload,
        control_connect_data,
        sunshine_feature_flags,
        negotiated_pyrowave,
        ref_pic_invalidation,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rtsp_parse_and_encrypted_roundtrip() {
        let raw = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 123456;timeout=90\r\nTransport: server_port=47998\r\n\r\nv=0\r\n";
        let parsed = parse_rtsp_response(raw).unwrap();
        assert_eq!(parsed.status_code, 200);
        assert_eq!(parsed.headers.get("cseq").map(|s| s.as_str()), Some("3"));
        assert_eq!(
            parse_port_from_transport(parsed.headers.get("transport").unwrap(), 0),
            47998
        );

        let cipher = Aes128::new(&[0x33u8; 16]);
        let enc = encrypt_rtsp_message(&cipher, 7, raw.as_bytes());
        // Simulate server direction tag ('S','R') for decrypt check
        let mut iv = [0u8; 12];
        iv[0..4].copy_from_slice(&7u32.to_le_bytes());
        iv[10] = b'C';
        iv[11] = b'R';
        let mut tag = [0u8; 16];
        tag.copy_from_slice(&enc[8..24]);
        let dec = cipher.decrypt_gcm(&iv, &[], &enc[24..], &tag).unwrap();
        assert_eq!(dec, raw.as_bytes());
    }

    #[test]
    fn test_persistent_rtsp_plaintext_and_encrypted_loopback() {
        use std::io::{BufRead, BufReader};
        use std::net::TcpListener;
        use std::thread;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();

        let server = thread::spawn(move || {
            let (mut conn, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(conn.try_clone().unwrap());
            for expected_cseq in 1..=2 {
                let mut header = String::new();
                loop {
                    let mut line = String::new();
                    let n = reader.read_line(&mut line).unwrap();
                    assert!(n > 0, "unexpected EOF on persistent connection");
                    header.push_str(&line);
                    if header.ends_with("\r\n\r\n") {
                        break;
                    }
                }
                assert!(header.contains(&format!("CSeq: {expected_cseq}")));
                let body = if expected_cseq == 2 { "v=0\r\no=vibemis\r\n" } else { "" };
                let resp = format!(
                    "RTSP/1.0 200 OK\r\nCSeq: {expected_cseq}\r\nContent-Length: {}\r\n\r\n{}",
                    body.len(),
                    body
                );
                conn.write_all(resp.as_bytes()).unwrap();
                conn.flush().unwrap();
            }
        });

        let mut client = open_rtsp_stream(SocketAddr::from(([127, 0, 0, 1], port))).unwrap();
        let req1 = build_rtsp_request("OPTIONS", "rtsp://127.0.0.1/", 1, None, &[], None);
        client.write_all(req1.as_bytes()).unwrap();
        client.flush().unwrap();
        let resp1 = read_plain_rtsp_response(&mut client).unwrap();
        assert_eq!(resp1.status_code, 200);
        assert_eq!(resp1.headers.get("cseq").map(|s| s.as_str()), Some("1"));
        assert_eq!(resp1.body, "");

        let req2 = build_rtsp_request("DESCRIBE", "rtsp://127.0.0.1/", 2, None, &[], None);
        client.write_all(req2.as_bytes()).unwrap();
        client.flush().unwrap();
        let resp2 = read_plain_rtsp_response(&mut client).unwrap();
        assert_eq!(resp2.status_code, 200);
        assert_eq!(resp2.headers.get("cseq").map(|s| s.as_str()), Some("2"));
        assert_eq!(resp2.body, "v=0\r\no=vibemis\r\n");

        server.join().unwrap();
    }

    #[test]
    fn test_read_encrypted_rtsp_response_framing_with_server_iv() {
        let cipher = Aes128::new(&[0x5Au8; 16]);
        let plain = b"RTSP/1.0 200 OK\r\nCSeq: 1\r\nSession: ABC;timeout=90\r\n\r\nv=0";
        let seq = 4u32;
        let mut iv = [0u8; 12];
        iv[0..4].copy_from_slice(&seq.to_le_bytes());
        iv[10] = b'H';
        iv[11] = b'R';
        let (ct, tag) = cipher.encrypt_gcm(&iv, &[], plain);
        let mut framed = Vec::new();
        framed.extend_from_slice(&((0x8000_0000u32) | (plain.len() as u32)).to_be_bytes());
        framed.extend_from_slice(&seq.to_be_bytes());
        framed.extend_from_slice(&tag);
        framed.extend_from_slice(&ct);

        let mut cursor = std::io::Cursor::new(framed);
        let resp = read_encrypted_rtsp_response(&mut cursor, &cipher).unwrap();
        assert_eq!(resp.status_code, 200);
        assert_eq!(resp.body, "v=0");
    }
}
