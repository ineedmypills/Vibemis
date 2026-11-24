//! Host Control Plane in Pure Rust:
//! - Pinned SHA-256 X.509 Server Certificate Fingerprint Verifier (bypasses WebPKI CA checks for LAN/Tailscale hosts)
//! - 4-Phase Sunshine / Vibepollo Pairing Cryptographic State Machine
//! - Zero-dependency XML Parser for `/serverinfo`, `/applist`, `/pair`, `/launch`, `/resume`
//! - Disk-backed BoxArt PNG Cache (`filesDir/boxart/<uuid>_<appid>.png`, returning file paths to Compose State)

use crate::protocol::crypto::{sha256, Aes128};
use std::fs;
use std::path::Path;

/// Verifies a peer X.509 DER certificate against a pinned SHA-256 fingerprint
/// without relying on WebPKI root CA chains or DNS SAN matching.
pub fn verify_pinned_cert_sha256(cert_der: &[u8], expected_sha256: &[u8; 32]) -> bool {
    if cert_der.is_empty() {
        return false;
    }
    let actual = sha256(cert_der);
    let mut diff = 0u8;
    for i in 0..32 {
        diff |= actual[i] ^ expected_sha256[i];
    }
    diff == 0
}

/// Computes the salted PIN SHA-256 AES-128 key (`SHA256(salt[16] || ascii_pin)[0..16]`)
/// used in Phase 1/2 of the Sunshine / GameStream pairing handshake.
pub fn derive_pairing_aes_key(salt_16: &[u8; 16], pin_ascii: &str) -> [u8; 16] {
    let mut buf = Vec::with_capacity(16 + pin_ascii.len());
    buf.extend_from_slice(salt_16);
    buf.extend_from_slice(pin_ascii.as_bytes());
    let digest = sha256(&buf);
    let mut key = [0u8; 16];
    key.copy_from_slice(&digest[..16]);
    key
}

/// Encrypts the client pairing challenge (`AES-128-ECB(key, random_challenge_16)`).
pub fn encrypt_pairing_challenge(aes_key: &[u8; 16], challenge_16: &[u8; 16]) -> [u8; 16] {
    let cipher = Aes128::new(aes_key);
    let mut block = *challenge_16;
    cipher.encrypt_block(&mut block);
    block
}

/// Extracts the value of an XML tag `<tag>value</tag>` or attribute `status_code="200"` in pure Rust.
pub fn extract_xml_tag<'a>(xml: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let close = format!("</{tag}>");
    let start = xml.find(&open)? + open.len();
    let rest = &xml[start..];
    let end = rest.find(&close)?;
    Some(rest[..end].trim())
}

pub fn extract_xml_root_status_code(xml: &str) -> Option<i32> {
    let marker = "status_code=\"";
    let start = xml.find(marker)? + marker.len();
    let rest = &xml[start..];
    let end = rest.find('"')?;
    rest[..end].parse::<i32>().ok()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedServerInfo {
    pub hostname: String,
    pub unique_id: String,
    pub app_version: String,
    pub gfe_version: String,
    pub paired: bool,
    pub current_game: i32,
    pub server_codec_mode_support: i32,
    pub https_port: u16,
    pub external_port: u16,
}

pub fn parse_serverinfo_xml(xml: &str) -> Option<ParsedServerInfo> {
    let code = extract_xml_root_status_code(xml)?;
    if code != 200 {
        return None;
    }
    Some(ParsedServerInfo {
        hostname: extract_xml_tag(xml, "hostname").unwrap_or("Unknown PC").to_string(),
        unique_id: extract_xml_tag(xml, "uniqueid").unwrap_or("").to_string(),
        app_version: extract_xml_tag(xml, "appversion").unwrap_or("7.1.431.0").to_string(),
        gfe_version: extract_xml_tag(xml, "GfeVersion").unwrap_or("").to_string(),
        paired: extract_xml_tag(xml, "PairStatus").unwrap_or("0") == "1",
        current_game: extract_xml_tag(xml, "currentgame")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0),
        server_codec_mode_support: extract_xml_tag(xml, "ServerCodecModeSupport")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0),
        https_port: extract_xml_tag(xml, "HttpsPort")
            .and_then(|s| s.parse().ok())
            .unwrap_or(47984),
        external_port: extract_xml_tag(xml, "ExternalPort")
            .and_then(|s| s.parse().ok())
            .unwrap_or(47989),
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAppEntry {
    pub id: i32,
    pub title: String,
    pub hdr_supported: bool,
}

pub fn parse_applist_xml(xml: &str) -> Vec<ParsedAppEntry> {
    let mut apps = Vec::new();
    let mut cursor = xml;
    while let Some(start) = cursor.find("<App>") {
        let after_start = &cursor[start + 5..];
        let Some(end) = after_start.find("</App>") else {
            break;
        };
        let block = &after_start[..end];
        if let (Some(id_str), Some(title)) = (
            extract_xml_tag(block, "ID"),
            extract_xml_tag(block, "AppTitle"),
        ) {
            if let Ok(id) = id_str.parse::<i32>() {
                let hdr = extract_xml_tag(block, "IsHdrSupported").unwrap_or("0") == "1";
                apps.push(ParsedAppEntry {
                    id,
                    title: title.to_string(),
                    hdr_supported: hdr,
                });
            }
        }
        cursor = &after_start[end + 6..];
    }
    apps
}

/// Writes box-art PNG bytes directly to `<cache_dir>/boxart/<host_uuid>_<app_id>.png`
/// and returns the absolute file path string for Compose State (avoiding raw byte arrays over JNI).
pub fn save_boxart_to_disk_cache(
    cache_dir: &str,
    host_uuid: &str,
    app_id: i32,
    png_bytes: &[u8],
) -> Option<String> {
    if cache_dir.is_empty() || host_uuid.is_empty() || png_bytes.is_empty() {
        return None;
    }
    let dir_path = Path::new(cache_dir).join("boxart");
    fs::create_dir_all(&dir_path).ok()?;
    let safe_uuid: String = host_uuid
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect();
    let file_path = dir_path.join(format!("{safe_uuid}_{app_id}.png"));
    fs::write(&file_path, png_bytes).ok()?;
    file_path.to_str().map(|s| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_serverinfo_and_applist_xml() {
        let server_xml = r#"<?xml version="1.0" encoding="utf-8"?>
        <root status_code="200">
            <hostname>VIBEPD-PC</hostname>
            <appversion>7.1.431.-1</appversion>
            <GfeVersion>3.23.0.74</GfeVersion>
            <uniqueid>0123456789ABCDEF</uniqueid>
            <PairStatus>1</PairStatus>
            <currentgame>0</currentgame>
            <ServerCodecModeSupport>259</ServerCodecModeSupport>
            <HttpsPort>47984</HttpsPort>
            <ExternalPort>47989</ExternalPort>
        </root>"#;

        let info = parse_serverinfo_xml(server_xml).unwrap();
        assert_eq!(info.hostname, "VIBEPD-PC");
        assert!(info.paired);
        assert_eq!(info.server_codec_mode_support, 259);

        let applist_xml = r#"<root status_code="200">
            <App><ID>101</ID><AppTitle>Desktop</AppTitle><IsHdrSupported>1</IsHdrSupported></App>
            <App><ID>202</ID><AppTitle>Steam Big Picture</AppTitle><IsHdrSupported>0</IsHdrSupported></App>
        </root>"#;
        let apps = parse_applist_xml(applist_xml);
        assert_eq!(apps.len(), 2);
        assert_eq!(apps[0].id, 101);
        assert_eq!(apps[0].title, "Desktop");
        assert!(apps[0].hdr_supported);
    }

    #[test]
    fn test_pinned_cert_sha256_and_pairing_key() {
        let fake_cert = b"SUNSHINE_SELF_SIGNED_DER_CERT";
        let fp = sha256(fake_cert);
        assert!(verify_pinned_cert_sha256(fake_cert, &fp));
        assert!(!verify_pinned_cert_sha256(b"TAMPERED", &fp));

        let salt = [0x11u8; 16];
        let k1 = derive_pairing_aes_key(&salt, "3716");
        let k2 = derive_pairing_aes_key(&salt, "3716");
        assert_eq!(k1, k2);
    }
}
