//! Pure Rust Input Packet Serializer and Encryptor for NVIDIA GameStream & Sunshine.
//! Pure Rust input packet serialization and encryption for Vibemis.

use crate::protocol::crypto::Aes128;

pub const KEY_DOWN_EVENT_MAGIC: u32 = 0x00000003;
pub const KEY_UP_EVENT_MAGIC: u32 = 0x00000004;
pub const MOUSE_MOVE_ABS_MAGIC: u32 = 0x00000005;
pub const MOUSE_MOVE_REL_MAGIC_GEN5: u32 = 0x00000007;
pub const MOUSE_BUTTON_DOWN_EVENT_MAGIC_GEN5: u32 = 0x00000008;
pub const MOUSE_BUTTON_UP_EVENT_MAGIC_GEN5: u32 = 0x00000009;
pub const SCROLL_MAGIC_GEN5: u32 = 0x0000000A;
pub const MULTI_CONTROLLER_MAGIC_GEN5: u32 = 0x0000000C;
pub const UTF8_TEXT_EVENT_MAGIC: u32 = 0x00000017;

pub const SS_HSCROLL_MAGIC: u32 = 0x55000001;
pub const SS_TOUCH_MAGIC: u32 = 0x55000002;
pub const SS_PEN_MAGIC: u32 = 0x55000003;
pub const SS_CONTROLLER_ARRIVAL_MAGIC: u32 = 0x55000004;
pub const SS_CONTROLLER_TOUCH_MAGIC: u32 = 0x55000005;
pub const SS_CONTROLLER_MOTION_MAGIC: u32 = 0x55000006;
pub const SS_CONTROLLER_BATTERY_MAGIC: u32 = 0x55000007;
pub const SS_CONTROLLER_SPEAKER_OWNER_MAGIC: u32 = 0x55000009;
pub const AP_SERVER_CMD_MAGIC: u32 = 0x80000000;

pub const BUTTON_ACTION_PRESS: i8 = 0x07;

fn write_input_header(buf: &mut Vec<u8>, payload_len_including_magic: u32, magic: u32) {
    buf.extend_from_slice(&payload_len_including_magic.to_be_bytes());
    buf.extend_from_slice(&magic.to_le_bytes());
}

pub fn serialize_mouse_move_rel(delta_x: i16, delta_y: i16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(12);
    write_input_header(&mut buf, 8, MOUSE_MOVE_REL_MAGIC_GEN5);
    buf.extend_from_slice(&delta_x.to_be_bytes());
    buf.extend_from_slice(&delta_y.to_be_bytes());
    buf
}

pub fn serialize_mouse_move_abs(x: i16, y: i16, width: i16, height: i16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(18);
    write_input_header(&mut buf, 14, MOUSE_MOVE_ABS_MAGIC);
    buf.extend_from_slice(&x.to_be_bytes());
    buf.extend_from_slice(&y.to_be_bytes());
    buf.extend_from_slice(&0i16.to_be_bytes());
    buf.extend_from_slice(&width.to_be_bytes());
    buf.extend_from_slice(&height.to_be_bytes());
    buf
}

pub fn serialize_mouse_button(button_event: i8, mouse_button: i8) -> Vec<u8> {
    let mut buf = Vec::with_capacity(9);
    let magic = if button_event == BUTTON_ACTION_PRESS {
        MOUSE_BUTTON_DOWN_EVENT_MAGIC_GEN5
    } else {
        MOUSE_BUTTON_UP_EVENT_MAGIC_GEN5
    };
    write_input_header(&mut buf, 5, magic);
    buf.push(mouse_button as u8);
    buf
}

pub fn serialize_keyboard_event(key_code: i16, key_action: i8, modifiers: i8, flags: i8) -> Vec<u8> {
    let mut buf = Vec::with_capacity(14);
    let magic = if key_action == 0x03 {
        KEY_DOWN_EVENT_MAGIC
    } else {
        KEY_UP_EVENT_MAGIC
    };
    write_input_header(&mut buf, 10, magic);
    buf.push(flags as u8);
    buf.extend_from_slice(&key_code.to_le_bytes());
    buf.push(modifiers as u8);
    buf.extend_from_slice(&0i16.to_le_bytes());
    buf
}

pub fn serialize_multi_controller(
    controller_number: i16,
    active_gamepad_mask: i16,
    button_flags: i32,
    left_trigger: i8,
    right_trigger: i8,
    left_stick_x: i16,
    left_stick_y: i16,
    right_stick_x: i16,
    right_stick_y: i16,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(34);
    write_input_header(&mut buf, 30, MULTI_CONTROLLER_MAGIC_GEN5);
    buf.extend_from_slice(&0x001Ai16.to_le_bytes());
    buf.extend_from_slice(&controller_number.to_le_bytes());
    buf.extend_from_slice(&active_gamepad_mask.to_le_bytes());
    buf.extend_from_slice(&0x0014i16.to_le_bytes());
    buf.extend_from_slice(&(button_flags as i16).to_le_bytes());
    buf.push(left_trigger as u8);
    buf.push(right_trigger as u8);
    buf.extend_from_slice(&left_stick_x.to_le_bytes());
    buf.extend_from_slice(&left_stick_y.to_le_bytes());
    buf.extend_from_slice(&right_stick_x.to_le_bytes());
    buf.extend_from_slice(&right_stick_y.to_le_bytes());
    buf.extend_from_slice(&0x009Ci16.to_le_bytes());
    buf.extend_from_slice(&((button_flags >> 16) as i16).to_le_bytes());
    buf.extend_from_slice(&0x0055i16.to_le_bytes());
    buf
}

pub fn serialize_high_res_scroll(scroll_amount: i16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(14);
    write_input_header(&mut buf, 10, SCROLL_MAGIC_GEN5);
    buf.extend_from_slice(&scroll_amount.to_be_bytes());
    buf.extend_from_slice(&scroll_amount.to_be_bytes());
    buf.extend_from_slice(&0i16.to_be_bytes());
    buf
}

pub fn serialize_high_res_hscroll(scroll_amount: i16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(10);
    write_input_header(&mut buf, 6, SS_HSCROLL_MAGIC);
    buf.extend_from_slice(&scroll_amount.to_be_bytes());
    buf
}

pub fn serialize_utf8_text(text: &str) -> Vec<u8> {
    let bytes = text.as_bytes();
    let len = bytes.len().min(32);
    let mut buf = Vec::with_capacity(8 + len);
    write_input_header(&mut buf, (4 + len) as u32, UTF8_TEXT_EVENT_MAGIC);
    buf.extend_from_slice(&bytes[..len]);
    buf
}

pub fn serialize_touch_event(
    event_type: u8,
    pointer_id: u32,
    x: f32,
    y: f32,
    pressure: f32,
    contact_area_major: f32,
    contact_area_minor: f32,
    rotation: u16,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(36);
    write_input_header(&mut buf, 32, SS_TOUCH_MAGIC);
    buf.push(event_type);
    buf.push(0);
    buf.extend_from_slice(&rotation.to_le_bytes());
    buf.extend_from_slice(&pointer_id.to_le_bytes());
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&pressure.to_le_bytes());
    buf.extend_from_slice(&contact_area_major.to_le_bytes());
    buf.extend_from_slice(&contact_area_minor.to_le_bytes());
    buf
}

pub fn serialize_pen_event(
    event_type: u8,
    tool_type: u8,
    pen_buttons: u8,
    x: f32,
    y: f32,
    pressure: f32,
    contact_area_major: f32,
    contact_area_minor: f32,
    rotation: u16,
    tilt: u8,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(36);
    write_input_header(&mut buf, 32, SS_PEN_MAGIC);
    buf.push(event_type);
    buf.push(tool_type);
    buf.push(pen_buttons);
    buf.push(0);
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&pressure.to_le_bytes());
    buf.extend_from_slice(&rotation.to_le_bytes());
    buf.push(tilt);
    buf.push(0);
    buf.extend_from_slice(&contact_area_major.to_le_bytes());
    buf.extend_from_slice(&contact_area_minor.to_le_bytes());
    buf
}

pub fn serialize_controller_arrival(
    controller_number: u8,
    _active_gamepad_mask: u16,
    ctype: u8,
    supported_button_flags: u32,
    capabilities: u16,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(16);
    write_input_header(&mut buf, 12, SS_CONTROLLER_ARRIVAL_MAGIC);
    buf.push(controller_number);
    buf.push(ctype);
    buf.extend_from_slice(&capabilities.to_le_bytes());
    buf.extend_from_slice(&supported_button_flags.to_le_bytes());
    buf
}

pub fn serialize_controller_touch(
    controller_number: u8,
    event_type: u8,
    pointer_id: u32,
    x: f32,
    y: f32,
    pressure: f32,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(28);
    write_input_header(&mut buf, 24, SS_CONTROLLER_TOUCH_MAGIC);
    buf.push(controller_number);
    buf.push(event_type);
    buf.extend_from_slice(&[0, 0]);
    buf.extend_from_slice(&pointer_id.to_le_bytes());
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&pressure.to_le_bytes());
    buf
}

pub fn serialize_controller_motion(
    controller_number: u8,
    motion_type: u8,
    x: f32,
    y: f32,
    z: f32,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(24);
    write_input_header(&mut buf, 20, SS_CONTROLLER_MOTION_MAGIC);
    buf.push(controller_number);
    buf.push(motion_type);
    buf.extend_from_slice(&[0, 0]);
    buf.extend_from_slice(&x.to_le_bytes());
    buf.extend_from_slice(&y.to_le_bytes());
    buf.extend_from_slice(&z.to_le_bytes());
    buf
}

pub fn serialize_controller_battery(
    controller_number: u8,
    battery_state: u8,
    battery_percentage: u8,
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(12);
    write_input_header(&mut buf, 8, SS_CONTROLLER_BATTERY_MAGIC);
    buf.push(controller_number);
    buf.push(battery_state);
    buf.push(battery_percentage);
    buf.push(0);
    buf
}

pub fn serialize_exec_server_cmd(cmd_id: u8) -> Vec<u8> {
    let mut buf = Vec::with_capacity(12);
    write_input_header(&mut buf, 8, AP_SERVER_CMD_MAGIC);
    buf.push(cmd_id);
    buf.extend_from_slice(&[0, 0, 0]);
    buf
}

/// Vibepollo DualSense speaker PCM owner binding packet (magic 0x55000009, 20 bytes total)
pub fn serialize_controller_speaker_owner_binding(slot: u16, owner_incarnation: u64) -> Vec<u8> {
    let mut buf = Vec::with_capacity(20);
    write_input_header(&mut buf, 16, SS_CONTROLLER_SPEAKER_OWNER_MAGIC);
    buf.extend_from_slice(&slot.to_le_bytes());
    buf.extend_from_slice(&1u16.to_le_bytes()); // version 1
    buf.extend_from_slice(&owner_incarnation.to_le_bytes());
    buf
}

pub fn encrypt_input_packet_cbc(cipher: &Aes128, base_iv: &[u8; 16], seq: u32, raw_packet: &[u8]) -> Vec<u8> {
    let mut iv = *base_iv;
    let iv_seq = u32::from_be_bytes([iv[0], iv[1], iv[2], iv[3]]).wrapping_add(seq);
    iv[0..4].copy_from_slice(&iv_seq.to_be_bytes());
    let encrypted = cipher.encrypt_cbc_pkcs7(&iv, raw_packet);
    let mut out = Vec::with_capacity(8 + encrypted.len());
    out.extend_from_slice(&((4 + encrypted.len()) as u32).to_be_bytes());
    out.extend_from_slice(&seq.to_be_bytes());
    out.extend_from_slice(&encrypted);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mouse_and_controller_serialization() {
        let rel = serialize_mouse_move_rel(12, -5);
        assert_eq!(rel.len(), 12);
        assert_eq!(&rel[0..4], &8u32.to_be_bytes());

        let mc = serialize_multi_controller(0, 1, 0x1000, 10, 20, 100, -100, 0, 0);
        assert_eq!(mc.len(), 34);
    }
}
