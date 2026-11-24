//! Pure Rust Wake-on-LAN (Magic Packet) builder and UDP broadcaster for Vibepollo / Sunshine hosts.

use std::net::{ToSocketAddrs, UdpSocket};

const STATIC_WOL_PORTS: [u16; 2] = [9, 47009];
const DYNAMIC_WOL_PORTS: [u16; 5] = [47998, 47999, 48000, 48002, 48010];

/// Parses a MAC address string (`AA:BB:CC:DD:EE:FF` or `AA-BB-CC-DD-EE-FF`) into 6 bytes.
pub fn parse_mac_address(mac_str: &str) -> Option<[u8; 6]> {
    let mut bytes = [0u8; 6];
    let parts: Vec<&str> = mac_str
        .trim()
        .split([':', '-'])
        .filter(|s| !s.is_empty())
        .collect();
    if parts.len() != 6 {
        return None;
    }
    for (i, part) in parts.iter().enumerate() {
        bytes[i] = u8::from_str_radix(part.trim(), 16).ok()?;
    }
    Some(bytes)
}

/// Builds a standard 102-byte Wake-on-LAN Magic Packet (`6x 0xFF` + `16x MAC`).
pub fn build_wol_payload(mac_str: &str) -> Option<[u8; 102]> {
    let mac = parse_mac_address(mac_str)?;
    let mut payload = [0xFFu8; 102];
    for i in 0..16 {
        let start = 6 + i * 6;
        payload[start..start + 6].copy_from_slice(&mac);
    }
    Some(payload)
}

fn send_to_ports(sock: &UdpSocket, host_ip: &str, http_port: u16, payload: &[u8; 102]) -> bool {
    let mut sent_any = false;

    for &port in &STATIC_WOL_PORTS {
        if let Ok(addrs) = (host_ip, port).to_socket_addrs() {
            for addr in addrs {
                if sock.send_to(payload, addr).is_ok() {
                    sent_any = true;
                }
            }
        }
    }

    for &base_port in &DYNAMIC_WOL_PORTS {
        let dynamic_port = base_port.saturating_sub(47989).saturating_add(http_port);
        if let Ok(addrs) = (host_ip, dynamic_port).to_socket_addrs() {
            for addr in addrs {
                if sock.send_to(payload, addr).is_ok() {
                    sent_any = true;
                }
            }
        }
    }

    sent_any
}

/// Broadcasts Wake-on-LAN magic packets to `255.255.255.255` and the target host address.
pub fn send_wol_packet(mac_str: &str, host_address: &str, http_port: u16) -> bool {
    let Some(payload) = build_wol_payload(mac_str) else {
        return false;
    };

    let mut sent_any = false;

    if let Ok(sock4) = UdpSocket::bind("0.0.0.0:0") {
        let _ = sock4.set_broadcast(true);
        if send_to_ports(&sock4, "255.255.255.255", http_port, &payload) {
            sent_any = true;
        }
        if !host_address.is_empty() && send_to_ports(&sock4, host_address, http_port, &payload) {
            sent_any = true;
        }
    }

    if host_address.contains(':') {
        if let Ok(sock6) = UdpSocket::bind("[::]:0") {
            if send_to_ports(&sock6, host_address, http_port, &payload) {
                sent_any = true;
            }
        }
    }

    sent_any
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_build_wol_payload() {
        let payload = build_wol_payload("00:11:22:AA:BB:CC").expect("valid MAC");
        assert_eq!(&payload[0..6], &[0xFF; 6]);
        for i in 0..16 {
            let offset = 6 + i * 6;
            assert_eq!(&payload[offset..offset + 6], &[0x00, 0x11, 0x22, 0xAA, 0xBB, 0xCC]);
        }
    }
}
