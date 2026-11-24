//! Pure Rust RFC 6762 / 6763 mDNS Discovery Client for `_nvstream._tcp.local`.
//! Replaces `org.jmdns:jmdns` on Android API < 34 without external crate dependencies.

use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const MDNS_MCAST_V4: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);
const MDNS_PORT: u16 = 5353;
const NVSTREAM_SERVICE_FQDN: &str = "_nvstream._tcp.local";

const QTYPE_A: u16 = 1;
const QTYPE_PTR: u16 = 12;
const QTYPE_AAAA: u16 = 28;
const QTYPE_SRV: u16 = 33;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredMdnsHost {
    pub instance_name: String,
    pub port: u16,
    pub v4_addrs: Vec<Ipv4Addr>,
    pub v6_addrs: Vec<Ipv6Addr>,
}

/// Builds an RFC 6762 PTR query packet for `_nvstream._tcp.local` (or custom service name).
pub fn build_mdns_ptr_query(service_fqdn: &str, unicast_response: bool) -> Vec<u8> {
    let mut pkt = Vec::with_capacity(64);
    // Transaction ID = 0, Flags = 0 (standard query), QDCOUNT = 1, AN/NS/AR = 0
    pkt.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in service_fqdn.trim_end_matches('.').split('.') {
        if !label.is_empty() && label.len() <= 63 {
            pkt.push(label.len() as u8);
            pkt.extend_from_slice(label.as_bytes());
        }
    }
    pkt.push(0); // root label
    pkt.extend_from_slice(&QTYPE_PTR.to_be_bytes());
    let qclass: u16 = if unicast_response { 0x8001 } else { 0x0001 };
    pkt.extend_from_slice(&qclass.to_be_bytes());
    pkt
}

/// Safely reads a compressed RFC 1035 DNS name with compression pointer loop protection.
pub fn read_dns_name(packet: &[u8], offset: usize) -> Option<(String, usize)> {
    let mut labels = Vec::new();
    let mut pos = offset;
    let mut end_offset = None;
    let mut jumps = 0u8;
    let mut total_bytes = 0usize;

    loop {
        let len = *packet.get(pos)?;
        if (len & 0xC0) == 0xC0 {
            let b2 = *packet.get(pos + 1)?;
            if end_offset.is_none() {
                end_offset = Some(pos + 2);
            }
            let ptr = (((len as usize) & 0x3F) << 8) | (b2 as usize);
            if ptr >= packet.len() || jumps >= 16 {
                return None;
            }
            pos = ptr;
            jumps += 1;
        } else if (len & 0xC0) != 0 {
            // Invalid reserved label type
            return None;
        } else if len == 0 {
            if end_offset.is_none() {
                end_offset = Some(pos + 1);
            }
            break;
        } else {
            let label_len = len as usize;
            let start = pos + 1;
            let end = start.checked_add(label_len)?;
            let slice = packet.get(start..end)?;
            total_bytes += label_len + 1;
            if total_bytes > 255 {
                return None;
            }
            labels.push(String::from_utf8_lossy(slice).into_owned());
            pos = end;
        }
    }

    Some((labels.join("."), end_offset.unwrap_or(pos)))
}

#[derive(Default)]
pub struct MdnsResolverState {
    // instance_fqdn_lower -> (display_name, port, target_host_lower)
    services: HashMap<String, (String, u16, String)>,
    // target_host_lower -> addresses
    v4_hosts: HashMap<String, Vec<Ipv4Addr>>,
    v6_hosts: HashMap<String, Vec<Ipv6Addr>>,
    emitted_keys: HashSet<String>,
    pending_queue: Vec<DiscoveredMdnsHost>,
}

impl MdnsResolverState {
    pub fn ingest_packet(&mut self, packet: &[u8]) {
        if packet.len() < 12 {
            return;
        }
        let flags = u16::from_be_bytes([packet[2], packet[3]]);
        // Only inspect DNS responses (QR bit 0x8000 set)
        if (flags & 0x8000) == 0 {
            return;
        }
        let qdcount = u16::from_be_bytes([packet[4], packet[5]]) as usize;
        let ancount = u16::from_be_bytes([packet[6], packet[7]]) as usize;
        let nscount = u16::from_be_bytes([packet[8], packet[9]]) as usize;
        let arcount = u16::from_be_bytes([packet[10], packet[11]]) as usize;

        let mut cursor = 12usize;
        for _ in 0..qdcount.min(64) {
            let Some((_, next)) = read_dns_name(packet, cursor) else {
                return;
            };
            if next + 4 > packet.len() {
                return;
            }
            cursor = next + 4;
        }

        let total_rrs = ancount.saturating_add(nscount).saturating_add(arcount).min(256);
        let mut saw_relevant = false;

        for _ in 0..total_rrs {
            let Some((rr_name, after_name)) = read_dns_name(packet, cursor) else {
                break;
            };
            if after_name + 10 > packet.len() {
                break;
            }
            let rtype = u16::from_be_bytes([packet[after_name], packet[after_name + 1]]);
            let _rclass = u16::from_be_bytes([packet[after_name + 2], packet[after_name + 3]]);
            let ttl = u32::from_be_bytes([
                packet[after_name + 4],
                packet[after_name + 5],
                packet[after_name + 6],
                packet[after_name + 7],
            ]);
            let rdlength = u16::from_be_bytes([packet[after_name + 8], packet[after_name + 9]]) as usize;
            let rdata_start = after_name + 10;
            let Some(rdata_end) = rdata_start.checked_add(rdlength) else {
                break;
            };
            if rdata_end > packet.len() {
                break;
            }
            cursor = rdata_end;

            if ttl == 0 {
                // Goodbye packet; skip adding
                continue;
            }

            let rr_lower = rr_name.to_ascii_lowercase();
            match rtype {
                QTYPE_PTR => {
                    if rr_lower == NVSTREAM_SERVICE_FQDN {
                        if let Some((ptr_target, _)) = read_dns_name(packet, rdata_start) {
                            let inst_lower = ptr_target.to_ascii_lowercase();
                            let display_name = strip_nvstream_suffix(&ptr_target);
                            self.services
                                .entry(inst_lower)
                                .or_insert_with(|| (display_name, 0, String::new()));
                            saw_relevant = true;
                        }
                    }
                }
                QTYPE_SRV => {
                    if rdlength >= 6 && rr_lower.ends_with("._nvstream._tcp.local") {
                        let port = u16::from_be_bytes([packet[rdata_start + 4], packet[rdata_start + 5]]);
                        if let Some((target_host, _)) = read_dns_name(packet, rdata_start + 6) {
                            let display_name = strip_nvstream_suffix(&rr_name);
                            self.services.insert(
                                rr_lower,
                                (display_name, port, target_host.to_ascii_lowercase()),
                            );
                            saw_relevant = true;
                        }
                    }
                }
                QTYPE_A => {
                    if rdlength == 4 {
                        let ip = Ipv4Addr::new(
                            packet[rdata_start],
                            packet[rdata_start + 1],
                            packet[rdata_start + 2],
                            packet[rdata_start + 3],
                        );
                        let list = self.v4_hosts.entry(rr_lower).or_default();
                        if !list.contains(&ip) {
                            list.push(ip);
                            saw_relevant = true;
                        }
                    }
                }
                QTYPE_AAAA => {
                    if rdlength == 16 {
                        let mut octets = [0u8; 16];
                        octets.copy_from_slice(&packet[rdata_start..rdata_end]);
                        let ip = Ipv6Addr::from(octets);
                        let list = self.v6_hosts.entry(rr_lower).or_default();
                        if !list.contains(&ip) {
                            list.push(ip);
                            saw_relevant = true;
                        }
                    }
                }
                _ => {}
            }
        }

        if saw_relevant {
            self.flush_resolved();
        }
    }

    fn flush_resolved(&mut self) {
        for (inst_lower, (display_name, port, target_lower)) in &self.services {
            if *port == 0 || target_lower.is_empty() {
                continue;
            }
            let v4 = self.v4_hosts.get(target_lower).cloned().unwrap_or_default();
            let v6 = self.v6_hosts.get(target_lower).cloned().unwrap_or_default();
            if v4.is_empty() && v6.is_empty() {
                continue;
            }
            let key = format!("{inst_lower}|{port}|{}|{}", v4.len(), v6.len());
            if self.emitted_keys.insert(key) {
                self.pending_queue.push(DiscoveredMdnsHost {
                    instance_name: display_name.clone(),
                    port: *port,
                    v4_addrs: v4,
                    v6_addrs: v6,
                });
            }
        }
    }

    pub fn drain_hosts(&mut self) -> Vec<DiscoveredMdnsHost> {
        std::mem::take(&mut self.pending_queue)
    }
}

fn strip_nvstream_suffix(fqdn: &str) -> String {
    let lower = fqdn.to_ascii_lowercase();
    let suffix = "._nvstream._tcp.local";
    if lower.ends_with(suffix) && fqdn.len() > suffix.len() {
        fqdn[..fqdn.len() - suffix.len()].to_string()
    } else {
        fqdn.to_string()
    }
}

struct MdnsWorker {
    stop_flag: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
    state: Arc<Mutex<MdnsResolverState>>,
}

static MDNS_WORKER: OnceLock<Mutex<Option<MdnsWorker>>> = OnceLock::new();

fn worker_slot() -> &'static Mutex<Option<MdnsWorker>> {
    MDNS_WORKER.get_or_init(|| Mutex::new(None))
}

#[cfg(unix)]
fn bind_mdns_socket() -> Option<UdpSocket> {
    use std::os::unix::io::FromRawFd;

    extern "C" {
        fn socket(domain: i32, ty: i32, protocol: i32) -> i32;
        fn setsockopt(fd: i32, level: i32, optname: i32, optval: *const u8, optlen: u32) -> i32;
        fn bind(fd: i32, addr: *const SockAddrIn, addrlen: u32) -> i32;
        fn close(fd: i32) -> i32;
    }

    #[repr(C)]
    struct SockAddrIn {
        sin_family: u16,
        sin_port: u16,
        sin_addr: u32,
        sin_zero: [u8; 8],
    }

    const AF_INET: i32 = 2;
    const SOCK_DGRAM: i32 = 2;
    const SOL_SOCKET: i32 = 1;
    const SO_REUSEADDR: i32 = 2;
    #[cfg(any(target_os = "android", target_os = "linux"))]
    const SO_REUSEPORT: i32 = 15;

    unsafe {
        let fd = socket(AF_INET, SOCK_DGRAM, 0);
        if fd >= 0 {
            let yes: i32 = 1;
            let _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes as *const i32 as *const u8, 4);
            #[cfg(any(target_os = "android", target_os = "linux"))]
            let _ = setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes as *const i32 as *const u8, 4);
            let sa = SockAddrIn {
                sin_family: AF_INET as u16,
                sin_port: MDNS_PORT.to_be(),
                sin_addr: 0,
                sin_zero: [0; 8],
            };
            if bind(fd, &sa as *const SockAddrIn, std::mem::size_of::<SockAddrIn>() as u32) == 0 {
                let sock = UdpSocket::from_raw_fd(fd);
                let _ = sock.join_multicast_v4(&MDNS_MCAST_V4, &Ipv4Addr::UNSPECIFIED);
                let _ = sock.set_read_timeout(Some(Duration::from_millis(250)));
                return Some(sock);
            }
            close(fd);
        }
    }

    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    let _ = sock.join_multicast_v4(&MDNS_MCAST_V4, &Ipv4Addr::UNSPECIFIED);
    let _ = sock.set_read_timeout(Some(Duration::from_millis(250)));
    Some(sock)
}

#[cfg(not(unix))]
fn bind_mdns_socket() -> Option<UdpSocket> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    let _ = sock.set_read_timeout(Some(Duration::from_millis(250)));
    Some(sock)
}

pub fn start_mdns_discovery(interval_ms: u32) -> bool {
    stop_mdns_discovery();

    let interval = Duration::from_millis((interval_ms as u64).clamp(500, 30_000));
    let stop_flag = Arc::new(AtomicBool::new(false));
    let state = Arc::new(Mutex::new(MdnsResolverState::default()));

    let stop_clone = Arc::clone(&stop_flag);
    let state_clone = Arc::clone(&state);

    let handle = thread::Builder::new()
        .name("RustMdnsDiscovery".into())
        .spawn(move || {
            let Some(sock) = bind_mdns_socket() else {
                return;
            };
            let mcast_target = SocketAddrV4::new(MDNS_MCAST_V4, MDNS_PORT);
            let qu_query = build_mdns_ptr_query(NVSTREAM_SERVICE_FQDN, true);
            let qm_query = build_mdns_ptr_query(NVSTREAM_SERVICE_FQDN, false);
            let mut last_query = Instant::now() - interval;
            let mut buf = [0u8; 4096];

            while !stop_clone.load(Ordering::Relaxed) {
                if last_query.elapsed() >= interval {
                    last_query = Instant::now();
                    let _ = sock.send_to(&qu_query, mcast_target);
                    let _ = sock.send_to(&qm_query, mcast_target);
                }
                if let Ok((len, _)) = sock.recv_from(&mut buf) {
                    if let Ok(mut st) = state_clone.lock() {
                        st.ingest_packet(&buf[..len]);
                    }
                }
            }
        })
        .ok();

    if handle.is_none() {
        return false;
    }

    if let Ok(mut slot) = worker_slot().lock() {
        *slot = Some(MdnsWorker {
            stop_flag,
            handle,
            state,
        });
        true
    } else {
        false
    }
}

pub fn stop_mdns_discovery() {
    let prev = worker_slot().lock().ok().and_then(|mut s| s.take());
    if let Some(mut w) = prev {
        w.stop_flag.store(true, Ordering::Release);
        if let Some(h) = w.handle.take() {
            let _ = h.join();
        }
    }
}

pub fn drain_mdns_discovered_encoded() -> String {
    let mut out = String::new();
    if let Ok(slot) = worker_slot().lock() {
        if let Some(ref w) = *slot {
            if let Ok(mut st) = w.state.lock() {
                for host in st.drain_hosts() {
                    let clean_name: String = host
                        .instance_name
                        .chars()
                        .map(|c| if c == '\t' || c == '\n' || c == '\r' { ' ' } else { c })
                        .collect();
                    let v4: Vec<String> = host.v4_addrs.iter().map(|a| a.to_string()).collect();
                    let v6: Vec<String> = host.v6_addrs.iter().map(|a| a.to_string()).collect();
                    out.push_str(&format!(
                        "{}\t{}\t{}\t{}\n",
                        clean_name,
                        host.port,
                        v4.join(","),
                        v6.join(",")
                    ));
                }
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode_dns_name(name: &str) -> Vec<u8> {
        let mut out = Vec::new();
        for label in name.trim_end_matches('.').split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
        out
    }

    #[test]
    fn test_mdns_query_and_compressed_response_parsing() {
        let q = build_mdns_ptr_query(NVSTREAM_SERVICE_FQDN, true);
        assert_eq!(q[4..6], 1u16.to_be_bytes());
        assert_eq!(read_dns_name(&q, 12).unwrap().0, "_nvstream._tcp.local");

        // Construct synthetic mDNS response with PTR -> SRV -> A & AAAA
        let mut pkt = vec![
            0x00, 0x00, // ID
            0x84, 0x00, // Response, Authoritative
            0x00, 0x00, // QDCOUNT=0
            0x00, 0x01, // ANCOUNT=1 (PTR)
            0x00, 0x00, // NSCOUNT=0
            0x00, 0x03, // ARCOUNT=3 (SRV, A, AAAA)
        ];

        // RR1: PTR _nvstream._tcp.local -> GamingPC._nvstream._tcp.local
        let ptr_name_off = pkt.len();
        pkt.extend_from_slice(&encode_dns_name("_nvstream._tcp.local"));
        pkt.extend_from_slice(&QTYPE_PTR.to_be_bytes());
        pkt.extend_from_slice(&1u16.to_be_bytes());
        pkt.extend_from_slice(&120u32.to_be_bytes());
        let inst_off = pkt.len() + 2;
        let inst_rdata = encode_dns_name("GamingPC._nvstream._tcp.local");
        pkt.extend_from_slice(&(inst_rdata.len() as u16).to_be_bytes());
        pkt.extend_from_slice(&inst_rdata);

        // RR2: SRV via compression pointer to inst_off -> target GamingPC.local:47989
        pkt.extend_from_slice(&[0xC0 | ((inst_off >> 8) as u8), (inst_off & 0xFF) as u8]);
        pkt.extend_from_slice(&QTYPE_SRV.to_be_bytes());
        pkt.extend_from_slice(&0x8001u16.to_be_bytes());
        pkt.extend_from_slice(&120u32.to_be_bytes());
        let target_rdata = encode_dns_name("GamingPC.local");
        let srv_rdlen = 6 + target_rdata.len();
        pkt.extend_from_slice(&(srv_rdlen as u16).to_be_bytes());
        pkt.extend_from_slice(&0u16.to_be_bytes()); // priority
        pkt.extend_from_slice(&0u16.to_be_bytes()); // weight
        pkt.extend_from_slice(&47989u16.to_be_bytes());
        let target_off = pkt.len();
        pkt.extend_from_slice(&target_rdata);

        // RR3: A via compression pointer to target_off -> 192.168.1.42
        pkt.extend_from_slice(&[0xC0 | ((target_off >> 8) as u8), (target_off & 0xFF) as u8]);
        pkt.extend_from_slice(&QTYPE_A.to_be_bytes());
        pkt.extend_from_slice(&0x8001u16.to_be_bytes());
        pkt.extend_from_slice(&120u32.to_be_bytes());
        pkt.extend_from_slice(&4u16.to_be_bytes());
        pkt.extend_from_slice(&[192, 168, 1, 42]);

        // RR4: AAAA via compression pointer to target_off -> 2001:db8::1
        pkt.extend_from_slice(&[0xC0 | ((target_off >> 8) as u8), (target_off & 0xFF) as u8]);
        pkt.extend_from_slice(&QTYPE_AAAA.to_be_bytes());
        pkt.extend_from_slice(&0x8001u16.to_be_bytes());
        pkt.extend_from_slice(&120u32.to_be_bytes());
        pkt.extend_from_slice(&16u16.to_be_bytes());
        let v6_ip = Ipv6Addr::new(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1);
        pkt.extend_from_slice(&v6_ip.octets());

        let _ = ptr_name_off;
        let mut st = MdnsResolverState::default();
        st.ingest_packet(&pkt);
        let hosts = st.drain_hosts();
        assert_eq!(hosts.len(), 1);
        assert_eq!(hosts[0].instance_name, "GamingPC");
        assert_eq!(hosts[0].port, 47989);
        assert_eq!(hosts[0].v4_addrs, vec![Ipv4Addr::new(192, 168, 1, 42)]);
        assert_eq!(hosts[0].v6_addrs, vec![v6_ip]);
    }

    #[test]
    fn test_mdns_compression_pointer_loop_rejected() {
        let malformed = [0xC0u8, 0x00u8];
        assert!(read_dns_name(&malformed, 0).is_none());
    }
}
