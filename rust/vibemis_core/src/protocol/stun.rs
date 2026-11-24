//! Pure Rust RFC 5389 STUN Binding Request client for IPv4 WAN address discovery.
//! Pure Rust STUN IPv4 discovery client for Vibemis.

use std::net::{Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const STUN_MESSAGE_BINDING_REQUEST: u16 = 0x0001;
const STUN_MESSAGE_BINDING_SUCCESS: u16 = 0x0101;
const STUN_MAGIC_COOKIE: u32 = 0x2112A442;
const STUN_ATTR_MAPPED_ADDRESS: u16 = 0x0001;
const STUN_ATTR_XOR_MAPPED_ADDRESS: u16 = 0x0020;

pub fn build_stun_binding_request(tx_id: &[u8; 12]) -> [u8; 20] {
    let mut req = [0u8; 20];
    req[0..2].copy_from_slice(&STUN_MESSAGE_BINDING_REQUEST.to_be_bytes());
    req[2..4].copy_from_slice(&0u16.to_be_bytes());
    req[4..8].copy_from_slice(&STUN_MAGIC_COOKIE.to_be_bytes());
    req[8..20].copy_from_slice(tx_id);
    req
}

pub fn parse_stun_binding_response(buf: &[u8], expected_tx_id: &[u8; 12]) -> Option<Ipv4Addr> {
    if buf.len() < 20 {
        return None;
    }
    let msg_type = u16::from_be_bytes([buf[0], buf[1]]);
    let msg_len = u16::from_be_bytes([buf[2], buf[3]]) as usize;
    let cookie = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    if msg_type != STUN_MESSAGE_BINDING_SUCCESS || cookie != STUN_MAGIC_COOKIE {
        return None;
    }
    if &buf[8..20] != expected_tx_id || buf.len() < 20 + msg_len {
        return None;
    }

    let mut offset = 20;
    let end = 20 + msg_len;
    while offset + 4 <= end {
        let attr_type = u16::from_be_bytes([buf[offset], buf[offset + 1]]) & 0x7FFF;
        let attr_len = u16::from_be_bytes([buf[offset + 2], buf[offset + 3]]) as usize;
        offset += 4;
        if offset + attr_len > end {
            return None;
        }
        if attr_len >= 8 && buf[offset + 1] == 0x01 {
            let raw_ip = u32::from_be_bytes([
                buf[offset + 4],
                buf[offset + 5],
                buf[offset + 6],
                buf[offset + 7],
            ]);
            if attr_type == STUN_ATTR_XOR_MAPPED_ADDRESS {
                return Some(Ipv4Addr::from(raw_ip ^ STUN_MAGIC_COOKIE));
            } else if attr_type == STUN_ATTR_MAPPED_ADDRESS {
                return Some(Ipv4Addr::from(raw_ip));
            }
        }
        let padded_len = (attr_len + 3) & !3;
        offset += padded_len;
    }
    None
}

pub fn find_external_address_ipv4(stun_host: &str, stun_port: u16) -> Option<Ipv4Addr> {
    let addrs: Vec<SocketAddr> = (stun_host, stun_port)
        .to_socket_addrs()
        .ok()?
        .filter(|a| a.is_ipv4())
        .collect();
    if addrs.is_empty() {
        return None;
    }

    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    let _ = sock.set_read_timeout(Some(Duration::from_millis(500)));

    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let mut tx_id = [0u8; 12];
    for (i, b) in tx_id.iter_mut().enumerate() {
        *b = ((nanos >> (i * 5)) as u8).wrapping_mul(31).wrapping_add(i as u8);
    }

    let req = build_stun_binding_request(&tx_id);
    let mut resp = [0u8; 512];

    for _attempt in 0..3 {
        for addr in &addrs {
            if sock.send_to(&req, addr).is_ok() {
                if let Ok((len, _)) = sock.recv_from(&mut resp) {
                    if let Some(ip) = parse_stun_binding_response(&resp[..len], &tx_id) {
                        return Some(ip);
                    }
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stun_xor_mapped_response_parsing() {
        let tx_id = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        let ip = Ipv4Addr::new(203, 0, 113, 42);
        let xored_ip = u32::from(ip) ^ STUN_MAGIC_COOKIE;

        let mut pkt = Vec::new();
        pkt.extend_from_slice(&STUN_MESSAGE_BINDING_SUCCESS.to_be_bytes());
        pkt.extend_from_slice(&12u16.to_be_bytes());
        pkt.extend_from_slice(&STUN_MAGIC_COOKIE.to_be_bytes());
        pkt.extend_from_slice(&tx_id);
        // Attribute header: XOR-MAPPED-ADDRESS (0x0020), len 8
        pkt.extend_from_slice(&STUN_ATTR_XOR_MAPPED_ADDRESS.to_be_bytes());
        pkt.extend_from_slice(&8u16.to_be_bytes());
        pkt.push(0x00);
        pkt.push(0x01); // IPv4
        pkt.extend_from_slice(&0x1234u16.to_be_bytes());
        pkt.extend_from_slice(&xored_ip.to_be_bytes());

        assert_eq!(parse_stun_binding_response(&pkt, &tx_id), Some(ip));
    }
}
