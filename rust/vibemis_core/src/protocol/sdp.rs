//! Pure Rust SDP Generator for NVIDIA GameStream and Sunshine RTSP ANNOUNCE.
//! Pure Rust SDP generator for Vibemis RTSP ANNOUNCE.

use crate::protocol::StreamConfig;

pub const VIDEO_FORMAT_MASK_H264: i32 = 0x000F;
pub const VIDEO_FORMAT_MASK_H265: i32 = 0x0F00;
pub const VIDEO_FORMAT_MASK_AV1: i32 = 0xF000;
pub const VIDEO_FORMAT_PYROWAVE: i32 = 0x010000;
pub const VIDEO_FORMAT_PYROWAVE_444: i32 = 0x020000;
pub const VIDEO_FORMAT_PYROWAVE_HDR10: i32 = 0x040000;
pub const VIDEO_FORMAT_PYROWAVE_HDR10_444: i32 = 0x080000;
pub const VIDEO_FORMAT_MASK_PYROWAVE: i32 = 0x0F0000;
pub const VIDEO_FORMAT_MASK_10BIT: i32 = 0x0CAA00;
pub const VIDEO_FORMAT_MASK_YUV444: i32 = 0x0ACC04;

pub const SCM_PYROWAVE: i32 = 0x00800000;
pub const SCM_PYROWAVE_444: i32 = 0x01000000;
pub const SCM_PYROWAVE_HDR10: i32 = 0x02000000;
pub const SCM_PYROWAVE_HDR10_444: i32 = 0x04000000;

pub const VB_FF_FEC_STATUS: u32 = 0x01;
pub const VB_FF_SESSION_ID_V1: u32 = 0x02;
pub const VB_FF_DUALSENSE_ACTUATOR_PCM: u32 = 0x04;
pub const VB_FF_CONTROLLER_SPEAKER_PCM: u32 = 0x08;

pub fn generate_sdp(
    config: &StreamConfig,
    target_address: &str,
    rtsp_client_version: i32,
    is_sunshine: bool,
    encryption_flags_enabled: u32,
) -> String {
    let mut sdp = String::with_capacity(2048);
    sdp.push_str("v=0\r\n");
    sdp.push_str(&format!(
        "o=android 0 {} IN IPv4 {}\r\n",
        rtsp_client_version, target_address
    ));
    sdp.push_str("s=NVIDIA Streaming Client\r\n");

    let mut csc = config.color_space;
    if (config.supported_video_formats & VIDEO_FORMAT_MASK_10BIT) != 0 {
        csc = 2; // Rec. 2020 for HDR
    }

    let channel_count = ((config.audio_configuration >> 8) & 0xFF) as u32;
    let channel_mask = ((config.audio_configuration >> 16) & 0xFFFF) as u32;
    let surround_params = (channel_mask << 16) | channel_count;

    let feature_flags = if is_sunshine {
        VB_FF_FEC_STATUS
            | VB_FF_SESSION_ID_V1
            | VB_FF_DUALSENSE_ACTUATOR_PCM
            | VB_FF_CONTROLLER_SPEAKER_PCM
    } else {
        VB_FF_FEC_STATUS | VB_FF_SESSION_ID_V1
    };
    let bit_stream_format = if (config.supported_video_formats & VIDEO_FORMAT_MASK_PYROWAVE) != 0 {
        3
    } else if (config.supported_video_formats & VIDEO_FORMAT_MASK_AV1) != 0 {
        2
    } else if (config.supported_video_formats & VIDEO_FORMAT_MASK_H265) != 0 {
        1
    } else {
        0
    };

    sdp.push_str(&format!(
        "a=x-nv-video[0].clientViewportWd:{}\r\n\
         a=x-nv-video[0].clientViewportHt:{}\r\n\
         a=x-nv-video[0].maxFPS:{}\r\n\
         a=x-nv-video[0].packetSize:{}\r\n\
         a=x-nv-video[0].rateControlMode:4\r\n\
         a=x-nv-video[0].timeoutLengthMs:7000\r\n\
         a=x-nv-video[0].framesWithInvalidRefThreshold:0\r\n\
         a=x-nv-video[0].initialBitrateKbps:{}\r\n\
         a=x-nv-video[0].initialPeakBitrateKbps:{}\r\n\
         a=x-nv-vqos[0].bw.minimumBitrateKbps:{}\r\n\
         a=x-nv-vqos[0].bw.maximumBitrateKbps:{}\r\n\
         a=x-nv-vqos[0].bitStreamFormat:{}\r\n\
         a=x-nv-vqos[0].fec.enable:1\r\n\
         a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000\r\n\
         a=x-nv-vqos[0].qosTrafficType:5\r\n\
         a=x-nv-aqos.qosTrafficType:4\r\n\
         a=x-nv-general.featureFlags:167\r\n\
         a=x-nv-general.useReliableUdp:13\r\n\
         a=x-nv-vqos[0].fec.minRequiredFecPackets:2\r\n\
         a=x-nv-vqos[0].bllFec.enable:0\r\n\
         a=x-nv-video[0].videoEncoderSlicesPerFrame:1\r\n\
         a=x-nv-video[0].dynamicRangeMode:{}\r\n\
         a=x-nv-video[0].maxNumReferenceFrames:1\r\n\
         a=x-nv-video[0].clientRefreshRateX100:{}\r\n\
         a=x-nv-video[0].encoderCscMode:{}\r\n\
         a=x-nv-audio.surround.numChannels:{}\r\n\
         a=x-nv-audio.surround.channelMask:{}\r\n\
         a=x-nv-audio.surround.enable:{}\r\n\
         a=x-nv-audio.surround.AudioQuality:1\r\n\
         a=x-nv-aqos.packetDuration:5\r\n\
         a=x-ml-general.featureFlags:{}\r\n",
        config.width,
        config.height,
        config.fps,
        config.packet_size,
        config.bitrate,
        config.bitrate,
        config.bitrate,
        config.bitrate,
        bit_stream_format,
        if (config.supported_video_formats & VIDEO_FORMAT_MASK_10BIT) != 0 { 1 } else { 0 },
        config.client_refresh_rate_x100,
        (csc << 1) | config.color_range,
        channel_count,
        channel_mask,
        if channel_count > 2 { 1 } else { 0 },
        feature_flags,
    ));

    if is_sunshine {
        let mut client_format_support = 0u32;
        if (config.supported_video_formats & VIDEO_FORMAT_MASK_H264) != 0 {
            client_format_support |= 0x0001;
        }
        if (config.supported_video_formats & VIDEO_FORMAT_MASK_H265) != 0 {
            client_format_support |= 0x0100;
        }
        if (config.supported_video_formats & VIDEO_FORMAT_MASK_AV1) != 0 {
            client_format_support |= 0x1000;
        }
        sdp.push_str(&format!(
            "a=x-ss-general.encryptionEnabled:{}\r\n\
             a=x-ml-video.configuredBitrateKbps:{}\r\n\
             a=x-ss-video[0].chromaSamplingType:{}\r\n",
            encryption_flags_enabled,
            config.bitrate,
            if (config.supported_video_formats & VIDEO_FORMAT_MASK_YUV444) != 0 { 1 } else { 0 }
        ));
        if bit_stream_format == 3 {
            sdp.push_str("a=x-ss-video[0].pyrowaveFeatures:1\r\n");
            sdp.push_str("a=x-ss-video[0].pyrowaveAdaptiveFec:0\r\n");
            sdp.push_str("a=x-ss-video[0].pyrowaveAdaptiveBitrate:0\r\n");
        }
        let _ = client_format_support;
        let _ = surround_params;
    }

    sdp.push_str("t=0 0\r\n");
    sdp.push_str("m=video 47998  \r\n");
    sdp
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sdp_generation() {
        let cfg = StreamConfig {
            width: 1920,
            height: 1080,
            fps: 60,
            bitrate: 20000,
            packet_size: 1024,
            streaming_remotely: 0,
            audio_configuration: (0x3 << 16) | (2 << 8) | 0xCA,
            supported_video_formats: 0x0101,
            client_refresh_rate_x100: 6000,
            color_space: 1,
            color_range: 0,
            encryption_flags: 1,
            ri_aes_key: [0u8; 16],
            ri_aes_iv: [0u8; 16],
            video_capabilities: 0,
        };
        let sdp = generate_sdp(&cfg, "192.168.1.50", 14, true, 1);
        assert!(sdp.contains("a=x-nv-video[0].clientViewportWd:1920"));
        assert!(sdp.contains("a=x-nv-video[0].clientViewportHt:1080"));
        assert!(sdp.contains("a=x-nv-video[0].maxFPS:60"));
        assert!(sdp.contains("a=x-ml-general.featureFlags:15"));

        let sdp_gfe = generate_sdp(&cfg, "192.168.1.50", 14, false, 0);
        assert!(sdp_gfe.contains("a=x-ml-general.featureFlags:3"));

        let mut pw_cfg = cfg;
        pw_cfg.supported_video_formats = VIDEO_FORMAT_PYROWAVE | 0x0101;
        let sdp_pw = generate_sdp(&pw_cfg, "192.168.1.50", 14, true, 1);
        assert!(sdp_pw.contains("a=x-nv-vqos[0].bitStreamFormat:3"));
        assert!(sdp_pw.contains("a=x-ss-video[0].pyrowaveFeatures:1"));
    }
}
