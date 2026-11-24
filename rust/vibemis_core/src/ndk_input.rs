//! Native Android NDK `ASensorManager` motion sensor polling, Linux `usbdevfs` asynchronous URB
//! gamepad driver (`USBDEVFS_DISCONNECT_CLAIM` + `USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURBNDELAY`),
//! and zero-allocation primitive gamepad/trackpad input math in Rust.

#![allow(unused_imports, unused_variables, dead_code)]

use crate::dsp_math;
use crate::ndk_sys::*;
use crate::protocol::{self, input};
use std::ffi::{c_int, c_short, c_uint, c_void, CString};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

static SENSOR_THREAD_STOP: AtomicBool = AtomicBool::new(true);
static DEADZONE_PERCENT: AtomicI32 = AtomicI32::new(5);
static TRACKPAD_SENS_X: AtomicI32 = AtomicI32::new(100);
static TRACKPAD_SENS_Y: AtomicI32 = AtomicI32::new(100);
static TRACKPAD_SWAP_AXIS: AtomicBool = AtomicBool::new(false);

pub fn configure_input_params(
    deadzone_pct: i32,
    trackpad_sens_x: i32,
    trackpad_sens_y: i32,
    swap_xy: bool,
) {
    DEADZONE_PERCENT.store(deadzone_pct.clamp(0, 50), Ordering::Relaxed);
    TRACKPAD_SENS_X.store(trackpad_sens_x.clamp(-500, 500), Ordering::Relaxed);
    TRACKPAD_SENS_Y.store(trackpad_sens_y.clamp(-500, 500), Ordering::Relaxed);
    TRACKPAD_SWAP_AXIS.store(swap_xy, Ordering::Relaxed);
}

/// Zero-allocation primitive gamepad input submission from Compose/Activity window.
/// Applies radial deadzone scaling in Rust and sends the serialized multi-controller packet.
pub fn submit_primitive_gamepad_state(
    controller_number: i16,
    active_gamepad_mask: i16,
    button_flags: i32,
    left_trigger: f32,
    right_trigger: f32,
    left_x: f32,
    left_y: f32,
    right_x: f32,
    right_y: f32,
) {
    let dz = (DEADZONE_PERCENT.load(Ordering::Relaxed) as f32) / 100.0;
    let packed_left = dsp_math::process_analog_stick_radial(left_x, -left_y, dz, 1.0);
    let packed_right = dsp_math::process_analog_stick_radial(right_x, -right_y, dz, 1.0);

    let lt_u8 = (left_trigger.clamp(0.0, 1.0) * 255.0) as u8 as i8;
    let rt_u8 = (right_trigger.clamp(0.0, 1.0) * 255.0) as u8 as i8;
    let lx_i16 = ((packed_left as u32) >> 16) as u16 as i16;
    let ly_i16 = ((packed_left as u32) & 0xFFFF) as u16 as i16;
    let rx_i16 = ((packed_right as u32) >> 16) as u16 as i16;
    let ry_i16 = ((packed_right as u32) & 0xFFFF) as u16 as i16;

    let pkt = input::serialize_multi_controller(
        controller_number,
        active_gamepad_mask,
        button_flags,
        lt_u8,
        rt_u8,
        lx_i16,
        ly_i16,
        rx_i16,
        ry_i16,
    );
    let _ = protocol::send_raw_input(&pkt);
}

/// Zero-allocation relative trackpad delta submission from Compose/View pointer input.
pub fn submit_primitive_trackpad_delta(raw_dx: f32, raw_dy: f32) {
    let swap = TRACKPAD_SWAP_AXIS.load(Ordering::Relaxed);
    let (in_x, in_y) = if swap { (raw_dy, raw_dx) } else { (raw_dx, raw_dy) };

    let sx = (TRACKPAD_SENS_X.load(Ordering::Relaxed) as f32) / 100.0;
    let sy = (TRACKPAD_SENS_Y.load(Ordering::Relaxed) as f32) / 100.0;

    let dx = (in_x * sx).round().clamp(-32767.0, 32767.0) as i16;
    let dy = (in_y * sy).round().clamp(-32767.0, 32767.0) as i16;
    if dx != 0 || dy != 0 {
        let pkt = input::serialize_mouse_move_rel(dx, dy);
        let _ = protocol::send_raw_input(&pkt);
    }
}

// Android NDK ASensorManager device motion polling (gyro + accelerometer).

#[repr(C)]
#[derive(Clone, Copy)]
struct ASensorEventRaw {
    version: i32,
    sensor: i32,
    sensor_type: i32,
    reserved0: i32,
    timestamp: i64,
    data: [f32; 16],
    flags: u32,
    reserved1: [i32; 3],
}

pub fn start_ndk_device_motion_sensors(controller_number: u8, rate_hz: u16) {
    if rate_hz == 0 {
        stop_ndk_device_motion_sensors();
        return;
    }
    if !SENSOR_THREAD_STOP.swap(false, Ordering::SeqCst) {
        return;
    }

    #[cfg(target_os = "android")]
    {
        let _ = thread::Builder::new()
            .name("RustNdkSensors".into())
            .spawn(move || unsafe {
                let pkg = CString::new("com.ineedmypills.vibemis").unwrap();
                let mgr = ASensorManager_getInstanceForPackage(pkg.as_ptr());
                if mgr.is_null() {
                    return;
                }
                let mut looper = ALooper_forThread();
                if looper.is_null() {
                    looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
                }
                if looper.is_null() {
                    return;
                }

                let queue = ASensorManager_createEventQueue(
                    mgr,
                    looper,
                    1,
                    None,
                    std::ptr::null_mut(),
                );
                if queue.is_null() {
                    return;
                }

                let period_us = (1_000_000 / (rate_hz.clamp(20, 250) as i32)).max(4000);
                let gyro = ASensorManager_getDefaultSensor(mgr, ASENSOR_TYPE_GYROSCOPE);
                let accel = ASensorManager_getDefaultSensor(mgr, ASENSOR_TYPE_ACCELEROMETER);

                if !gyro.is_null() {
                    let _ = ASensorEventQueue_enableSensor(queue, gyro);
                    let _ = ASensorEventQueue_setEventRate(queue, gyro, period_us);
                }
                if !accel.is_null() {
                    let _ = ASensorEventQueue_enableSensor(queue, accel);
                    let _ = ASensorEventQueue_setEventRate(queue, accel, period_us);
                }

                let mut events = [ASensorEventRaw {
                    version: 0,
                    sensor: 0,
                    sensor_type: 0,
                    reserved0: 0,
                    timestamp: 0,
                    data: [0.0; 16],
                    flags: 0,
                    reserved1: [0; 3],
                }; 8];

                const RAD_TO_DEG: f32 = 180.0 / std::f32::consts::PI;

                while !SENSOR_THREAD_STOP.load(Ordering::Relaxed) {
                    let _ = ALooper_pollOnce(
                        25,
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                    );
                    loop {
                        let n = ASensorEventQueue_getEvents(
                            queue,
                            events.as_mut_ptr() as *mut c_void,
                            events.len(),
                        );
                        if n <= 0 {
                            break;
                        }
                        for ev in &events[..(n as usize).min(events.len())] {
                            if ev.sensor_type == ASENSOR_TYPE_GYROSCOPE {
                                let pkt = input::serialize_controller_motion(
                                    controller_number,
                                    0x02, // VB_MOTION_TYPE_GYRO (deg/s)
                                    ev.data[0] * RAD_TO_DEG,
                                    ev.data[1] * RAD_TO_DEG,
                                    ev.data[2] * RAD_TO_DEG,
                                );
                                let _ = protocol::send_raw_input(&pkt);
                            } else if ev.sensor_type == ASENSOR_TYPE_ACCELEROMETER {
                                let pkt = input::serialize_controller_motion(
                                    controller_number,
                                    0x01, // VB_MOTION_TYPE_ACCEL (m/s^2)
                                    ev.data[0],
                                    ev.data[1],
                                    ev.data[2],
                                );
                                let _ = protocol::send_raw_input(&pkt);
                            }
                        }
                    }
                }

                if !gyro.is_null() {
                    let _ = ASensorEventQueue_disableSensor(queue, gyro);
                }
                if !accel.is_null() {
                    let _ = ASensorEventQueue_disableSensor(queue, accel);
                }
                let _ = ASensorManager_destroyEventQueue(mgr, queue);
            });
    }
}

pub fn stop_ndk_device_motion_sensors() {
    SENSOR_THREAD_STOP.store(true, Ordering::SeqCst);
}

// Linux usbdevfs asynchronous URB gamepad driver via UsbDeviceConnection.fileDescriptor.

const USBDEVFS_URB_TYPE_INTERRUPT: u8 = 1;
const USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER: c_uint = 0x02;

#[repr(C)]
struct UsbdevfsDisconnectClaim {
    interface: c_uint,
    flags: c_uint,
    driver: [u8; 256],
}

#[repr(C)]
struct UsbdevfsUrb {
    urb_type: u8,
    endpoint: u8,
    status: c_int,
    flags: c_uint,
    buffer: *mut c_void,
    buffer_length: c_int,
    actual_length: c_int,
    start_frame: c_int,
    number_of_packets: c_int,
    error_count: c_int,
    signr: c_uint,
    usercontext: *mut c_void,
}

#[cfg(any(target_os = "android", target_os = "linux"))]
#[repr(C)]
struct PollFd {
    fd: c_int,
    events: c_short,
    revents: c_short,
}

#[cfg(any(target_os = "android", target_os = "linux"))]
const POLLOUT: c_short = 0x0004;
#[cfg(any(target_os = "android", target_os = "linux"))]
const POLLERR: c_short = 0x0008;
#[cfg(any(target_os = "android", target_os = "linux"))]
const POLLHUP: c_short = 0x0010;
#[cfg(any(target_os = "android", target_os = "linux"))]
const POLLNVAL: c_short = 0x0020;

#[cfg(any(target_os = "android", target_os = "linux"))]
extern "C" {
    fn ioctl(fd: c_int, request: usize, ...) -> c_int;
    fn poll(fds: *mut PollFd, nfds: usize, timeout: c_int) -> c_int;
}

// Linux _IOW / _IOR ioctl numbers for usbdevfs ('U' = 0x55)
#[cfg(target_pointer_width = "64")]
const USBDEVFS_SUBMITURB_IOCTL: usize = 0x8038_550A;
#[cfg(target_pointer_width = "32")]
const USBDEVFS_SUBMITURB_IOCTL: usize = 0x802C_550A;

#[cfg(target_pointer_width = "64")]
const USBDEVFS_REAPURBNDELAY_IOCTL: usize = 0x4008_550D;
#[cfg(target_pointer_width = "32")]
const USBDEVFS_REAPURBNDELAY_IOCTL: usize = 0x4004_550D;

const USBDEVFS_DISCONNECT_CLAIM_IOCTL: usize = 0x8108_551B;

pub struct UsbUrbGamepadWorker {
    stop_flag: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
}

impl UsbUrbGamepadWorker {
    /// Starts an asynchronous URB worker on the raw `fd` obtained from `UsbDeviceConnection.fileDescriptor`.
    /// Note: Kotlin must hold a strong reference to `UsbDeviceConnection` for the lifetime of this worker.
    pub fn start(
        fd: i32,
        interface_num: u32,
        in_endpoint: u8,
        vendor_id: u16,
        product_id: u16,
        controller_number: i16,
    ) -> Self {
        let stop_flag = Arc::new(AtomicBool::new(false));
        let stop_clone = Arc::clone(&stop_flag);

        let worker = thread::Builder::new()
            .name(format!("RustUsbUrb-{controller_number}"))
            .spawn(move || {
                #[cfg(any(target_os = "android", target_os = "linux"))]
                unsafe {
                    let mut dc = UsbdevfsDisconnectClaim {
                        interface: interface_num,
                        flags: USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER,
                        driver: [0u8; 256],
                    };
                    dc.driver[..6].copy_from_slice(b"usbfs\0");
                    let _ = ioctl(fd, USBDEVFS_DISCONNECT_CLAIM_IOCTL, &mut dc as *mut _);

                    let mut report_buf = [0u8; 64];
                    let mut urb = UsbdevfsUrb {
                        urb_type: USBDEVFS_URB_TYPE_INTERRUPT,
                        endpoint: in_endpoint | 0x80,
                        status: 0,
                        flags: 0,
                        buffer: report_buf.as_mut_ptr() as *mut c_void,
                        buffer_length: report_buf.len() as c_int,
                        actual_length: 0,
                        start_frame: 0,
                        number_of_packets: 0,
                        error_count: 0,
                        signr: 0,
                        usercontext: std::ptr::null_mut(),
                    };

                    if ioctl(fd, USBDEVFS_SUBMITURB_IOCTL, &mut urb as *mut _) < 0 {
                        return;
                    }

                    while !stop_clone.load(Ordering::Relaxed) {
                        let mut reaped: *mut UsbdevfsUrb = std::ptr::null_mut();
                        let rc = ioctl(fd, USBDEVFS_REAPURBNDELAY_IOCTL, &mut reaped as *mut _);
                        if rc == 0 && !reaped.is_null() {
                            let len = (*reaped).actual_length.clamp(0, 64) as usize;
                            if len > 0 {
                                parse_and_send_usb_report(
                                    vendor_id,
                                    product_id,
                                    controller_number,
                                    &report_buf[..len],
                                );
                            }
                            if !stop_clone.load(Ordering::Relaxed) {
                                if ioctl(fd, USBDEVFS_SUBMITURB_IOCTL, &mut urb as *mut _) < 0 {
                                    break;
                                }
                            }
                        } else {
                            let mut pfd = PollFd {
                                fd,
                                events: POLLOUT,
                                revents: 0,
                            };
                            let prc = poll(&mut pfd as *mut PollFd, 1, 25);
                            if prc > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 {
                                break;
                            }
                        }
                    }
                }
            })
            .ok();

        Self { stop_flag, worker }
    }

    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::Release);
        if let Some(h) = self.worker.take() {
            let _ = h.join();
        }
    }
}

/// Parses Xbox One/Series (`0x20` input report) and Xbox 360 (`0x00, 0x14`) USB reports in pure Rust.
pub fn parse_and_send_usb_report(
    vendor_id: u16,
    _product_id: u16,
    controller_number: i16,
    report: &[u8],
) {
    // Xbox One / Series GIP input report (0x20)
    if vendor_id == 0x045E && report.len() >= 18 && report[0] == 0x20 {
        let b4 = report[4];
        let b5 = report[5];
        let mut btns = 0i32;
        if (b4 & 0x04) != 0 { btns |= 0x0400; } // Start
        if (b4 & 0x08) != 0 { btns |= 0x0800; } // Back
        if (b4 & 0x10) != 0 { btns |= 0x1000; } // A
        if (b4 & 0x20) != 0 { btns |= 0x2000; } // B
        if (b4 & 0x40) != 0 { btns |= 0x4000; } // X
        if (b4 & 0x80) != 0 { btns |= 0x8000; } // Y
        if (b5 & 0x01) != 0 { btns |= 0x0001; } // D-Pad Up
        if (b5 & 0x02) != 0 { btns |= 0x0002; } // D-Pad Down
        if (b5 & 0x04) != 0 { btns |= 0x0004; } // D-Pad Left
        if (b5 & 0x08) != 0 { btns |= 0x0008; } // D-Pad Right
        if (b5 & 0x10) != 0 { btns |= 0x0100; } // LB
        if (b5 & 0x20) != 0 { btns |= 0x0200; } // RB
        if (b5 & 0x40) != 0 { btns |= 0x0040; } // LS
        if (b5 & 0x80) != 0 { btns |= 0x0080; } // RS

        let lt = (u16::from_le_bytes([report[6], report[7]]).min(1023) as f32) / 1023.0;
        let rt = (u16::from_le_bytes([report[8], report[9]]).min(1023) as f32) / 1023.0;
        let lx = (i16::from_le_bytes([report[10], report[11]]) as f32) / 32767.0;
        let ly = -(i16::from_le_bytes([report[12], report[13]]) as f32) / 32767.0;
        let rx = (i16::from_le_bytes([report[14], report[15]]) as f32) / 32767.0;
        let ry = -(i16::from_le_bytes([report[16], report[17]]) as f32) / 32767.0;

        submit_primitive_gamepad_state(
            controller_number,
            1 << controller_number,
            btns,
            lt,
            rt,
            lx,
            ly,
            rx,
            ry,
        );
    }
}
