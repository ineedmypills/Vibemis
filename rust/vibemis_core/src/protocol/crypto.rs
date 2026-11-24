//! Pure Rust AES-128 block cipher, AES-128-CBC, and AES-128-GCM implementation
//! for Vibemis input, audio, video, and RTSP stream encryption.

const SBOX: [u8; 256] = [
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
];

const INV_SBOX: [u8; 256] = [
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d,
];

const RCON: [u8; 10] = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];

#[inline]
fn xtime(x: u8) -> u8 {
    (x << 1) ^ if (x & 0x80) != 0 { 0x1b } else { 0 }
}

#[inline]
fn gf_mul(mut a: u8, mut b: u8) -> u8 {
    let mut p = 0u8;
    for _ in 0..8 {
        if (b & 1) != 0 {
            p ^= a;
        }
        let hi = a & 0x80;
        a <<= 1;
        if hi != 0 {
            a ^= 0x1b;
        }
        b >>= 1;
    }
    p
}

#[derive(Clone)]
pub struct Aes128 {
    round_keys: [[u8; 16]; 11],
}

impl Aes128 {
    pub fn new(key: &[u8; 16]) -> Self {
        let mut w = [[0u8; 4]; 44];
        for i in 0..4 {
            w[i] = [key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]];
        }
        for i in 4..44 {
            let mut temp = w[i - 1];
            if i % 4 == 0 {
                temp = [
                    SBOX[temp[1] as usize] ^ RCON[(i / 4) - 1],
                    SBOX[temp[2] as usize],
                    SBOX[temp[3] as usize],
                    SBOX[temp[0] as usize],
                ];
            }
            let prev = w[i - 4];
            w[i] = [
                prev[0] ^ temp[0],
                prev[1] ^ temp[1],
                prev[2] ^ temp[2],
                prev[3] ^ temp[3],
            ];
        }
        let mut round_keys = [[0u8; 16]; 11];
        for r in 0..11 {
            for c in 0..4 {
                round_keys[r][4 * c..4 * c + 4].copy_from_slice(&w[4 * r + c]);
            }
        }
        Self { round_keys }
    }

    pub fn encrypt_block(&self, block: &mut [u8; 16]) {
        #[cfg(target_arch = "aarch64")]
        if std::arch::is_aarch64_feature_detected!("aes") {
            unsafe {
                self.encrypt_block_aarch64(block);
            }
            return;
        }
        self.encrypt_block_sw(block);
    }

    pub fn decrypt_block(&self, block: &mut [u8; 16]) {
        #[cfg(target_arch = "aarch64")]
        if std::arch::is_aarch64_feature_detected!("aes") {
            unsafe {
                self.decrypt_block_aarch64(block);
            }
            return;
        }
        self.decrypt_block_sw(block);
    }

    #[cfg(target_arch = "aarch64")]
    #[target_feature(enable = "aes")]
    unsafe fn encrypt_block_aarch64(&self, block: &mut [u8; 16]) {
        use std::arch::aarch64::*;
        let mut state = vld1q_u8(block.as_ptr());
        for r in 0..9 {
            let rk = vld1q_u8(self.round_keys[r].as_ptr());
            state = vaesmcq_u8(vaeseq_u8(state, rk));
        }
        let rk9 = vld1q_u8(self.round_keys[9].as_ptr());
        let rk10 = vld1q_u8(self.round_keys[10].as_ptr());
        state = veorq_u8(vaeseq_u8(state, rk9), rk10);
        vst1q_u8(block.as_mut_ptr(), state);
    }

    #[cfg(target_arch = "aarch64")]
    #[target_feature(enable = "aes")]
    unsafe fn decrypt_block_aarch64(&self, block: &mut [u8; 16]) {
        use std::arch::aarch64::*;
        // Inverse key schedule equivalents for ARMv8 AESD+AESIMC (InvMixColumns on round keys 1..9)
        let mut state = vld1q_u8(block.as_ptr());
        let rk10 = vld1q_u8(self.round_keys[10].as_ptr());
        state = vaesdq_u8(state, rk10);
        state = vaesimcq_u8(state);
        for r in (2..10).rev() {
            let rk_inv = vaesimcq_u8(vld1q_u8(self.round_keys[r].as_ptr()));
            state = vaesimcq_u8(vaesdq_u8(state, rk_inv));
        }
        let rk1_inv = vaesimcq_u8(vld1q_u8(self.round_keys[1].as_ptr()));
        let rk0 = vld1q_u8(self.round_keys[0].as_ptr());
        state = veorq_u8(vaesdq_u8(state, rk1_inv), rk0);
        vst1q_u8(block.as_mut_ptr(), state);
    }

    fn encrypt_block_sw(&self, block: &mut [u8; 16]) {
        for i in 0..16 {
            block[i] ^= self.round_keys[0][i];
        }
        for round in 1..10 {
            for b in block.iter_mut() {
                *b = SBOX[*b as usize];
            }
            Self::shift_rows(block);
            Self::mix_columns(block);
            for i in 0..16 {
                block[i] ^= self.round_keys[round][i];
            }
        }
        for b in block.iter_mut() {
            *b = SBOX[*b as usize];
        }
        Self::shift_rows(block);
        for i in 0..16 {
            block[i] ^= self.round_keys[10][i];
        }
    }

    fn decrypt_block_sw(&self, block: &mut [u8; 16]) {
        for i in 0..16 {
            block[i] ^= self.round_keys[10][i];
        }
        for round in (1..10).rev() {
            Self::inv_shift_rows(block);
            for b in block.iter_mut() {
                *b = INV_SBOX[*b as usize];
            }
            for i in 0..16 {
                block[i] ^= self.round_keys[round][i];
            }
            Self::inv_mix_columns(block);
        }
        Self::inv_shift_rows(block);
        for b in block.iter_mut() {
            *b = INV_SBOX[*b as usize];
        }
        for i in 0..16 {
            block[i] ^= self.round_keys[0][i];
        }
    }

    #[inline]
    fn shift_rows(s: &mut [u8; 16]) {
        let t = s[1];
        s[1] = s[5];
        s[5] = s[9];
        s[9] = s[13];
        s[13] = t;

        s.swap(2, 10);
        s.swap(6, 14);

        let t = s[15];
        s[15] = s[11];
        s[11] = s[7];
        s[7] = s[3];
        s[3] = t;
    }

    #[inline]
    fn inv_shift_rows(s: &mut [u8; 16]) {
        let t = s[13];
        s[13] = s[9];
        s[9] = s[5];
        s[5] = s[1];
        s[1] = t;

        s.swap(2, 10);
        s.swap(6, 14);

        let t = s[3];
        s[3] = s[7];
        s[7] = s[11];
        s[11] = s[15];
        s[15] = t;
    }

    #[inline]
    fn mix_columns(s: &mut [u8; 16]) {
        for c in 0..4 {
            let i = c * 4;
            let a0 = s[i];
            let a1 = s[i + 1];
            let a2 = s[i + 2];
            let a3 = s[i + 3];
            let t = a0 ^ a1 ^ a2 ^ a3;
            s[i] ^= t ^ xtime(a0 ^ a1);
            s[i + 1] ^= t ^ xtime(a1 ^ a2);
            s[i + 2] ^= t ^ xtime(a2 ^ a3);
            s[i + 3] ^= t ^ xtime(a3 ^ a0);
        }
    }

    #[inline]
    fn inv_mix_columns(s: &mut [u8; 16]) {
        for c in 0..4 {
            let i = c * 4;
            let a0 = s[i];
            let a1 = s[i + 1];
            let a2 = s[i + 2];
            let a3 = s[i + 3];
            s[i] = gf_mul(a0, 0x0e) ^ gf_mul(a1, 0x0b) ^ gf_mul(a2, 0x0d) ^ gf_mul(a3, 0x09);
            s[i + 1] = gf_mul(a0, 0x09) ^ gf_mul(a1, 0x0e) ^ gf_mul(a2, 0x0b) ^ gf_mul(a3, 0x0d);
            s[i + 2] = gf_mul(a0, 0x0d) ^ gf_mul(a1, 0x09) ^ gf_mul(a2, 0x0e) ^ gf_mul(a3, 0x0b);
            s[i + 3] = gf_mul(a0, 0x0b) ^ gf_mul(a1, 0x0d) ^ gf_mul(a2, 0x09) ^ gf_mul(a3, 0x0e);
        }
    }

    /// AES-128-ECB encryption (16-byte block-rounded, matching Sunshine/Vibepollo pairing protocol)
    pub fn encrypt_ecb(&self, plaintext: &[u8]) -> Vec<u8> {
        let rounded_len = (plaintext.len() + 15) & !15;
        let mut out = vec![0u8; rounded_len];
        out[..plaintext.len()].copy_from_slice(plaintext);
        for chunk in out.chunks_exact_mut(16) {
            let mut block = [0u8; 16];
            block.copy_from_slice(chunk);
            self.encrypt_block(&mut block);
            chunk.copy_from_slice(&block);
        }
        out
    }

    /// AES-128-ECB decryption (16-byte block-rounded, matching Sunshine/Vibepollo pairing protocol)
    pub fn decrypt_ecb(&self, ciphertext: &[u8]) -> Vec<u8> {
        let rounded_len = (ciphertext.len() + 15) & !15;
        let mut out = vec![0u8; rounded_len];
        out[..ciphertext.len()].copy_from_slice(ciphertext);
        for chunk in out.chunks_exact_mut(16) {
            let mut block = [0u8; 16];
            block.copy_from_slice(chunk);
            self.decrypt_block(&mut block);
            chunk.copy_from_slice(&block);
        }
        out
    }

    /// AES-128-CBC encryption with PKCS#7 padding
    pub fn encrypt_cbc_pkcs7(&self, iv: &[u8; 16], plaintext: &[u8]) -> Vec<u8> {
        let pad_len = 16 - (plaintext.len() % 16);
        let total_len = plaintext.len() + pad_len;
        let mut out = Vec::with_capacity(total_len);
        let mut prev = *iv;

        let mut offset = 0;
        while offset < total_len {
            let mut block = [pad_len as u8; 16];
            let rem = plaintext.len().saturating_sub(offset);
            let copy_len = rem.min(16);
            if copy_len > 0 {
                block[..copy_len].copy_from_slice(&plaintext[offset..offset + copy_len]);
            }
            for i in 0..16 {
                block[i] ^= prev[i];
            }
            self.encrypt_block(&mut block);
            prev = block;
            out.extend_from_slice(&block);
            offset += 16;
        }
        out
    }

    /// AES-128-CBC decryption (raw blocks, caller strips padding if applicable)
    pub fn decrypt_cbc(&self, iv: &[u8; 16], ciphertext: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity((ciphertext.len() / 16) * 16);
        self.decrypt_cbc_into(iv, ciphertext, &mut out);
        out
    }

    /// Zero-allocation AES-128-CBC decryption into caller-owned buffer
    pub fn decrypt_cbc_into(&self, iv: &[u8; 16], ciphertext: &[u8], out: &mut Vec<u8>) {
        out.clear();
        let blocks = ciphertext.len() / 16;
        out.reserve(blocks * 16);
        let mut prev = *iv;

        for b in 0..blocks {
            let mut block = [0u8; 16];
            block.copy_from_slice(&ciphertext[b * 16..(b + 1) * 16]);
            let next_prev = block;
            self.decrypt_block(&mut block);
            for i in 0..16 {
                block[i] ^= prev[i];
            }
            prev = next_prev;
            out.extend_from_slice(&block);
        }
    }

    /// AES-128-GCM authenticated encryption (12-byte IV, 16-byte tag)
    pub fn encrypt_gcm(&self, iv: &[u8; 12], aad: &[u8], plaintext: &[u8]) -> (Vec<u8>, [u8; 16]) {
        let mut h = [0u8; 16];
        self.encrypt_block(&mut h);

        let mut j0 = [0u8; 16];
        j0[..12].copy_from_slice(iv);
        j0[15] = 1;

        let mut ciphertext = Vec::with_capacity(plaintext.len());
        let mut ctr = j0;
        for chunk in plaintext.chunks(16) {
            inc32(&mut ctr);
            let mut keystream = ctr;
            self.encrypt_block(&mut keystream);
            for (i, &b) in chunk.iter().enumerate() {
                ciphertext.push(b ^ keystream[i]);
            }
        }

        let mut tag = ghash(&h, aad, &ciphertext);
        let mut enc_j0 = j0;
        self.encrypt_block(&mut enc_j0);
        for i in 0..16 {
            tag[i] ^= enc_j0[i];
        }

        (ciphertext, tag)
    }

    /// AES-128-GCM authenticated decryption
    pub fn decrypt_gcm(
        &self,
        iv: &[u8; 12],
        aad: &[u8],
        ciphertext: &[u8],
        expected_tag: &[u8; 16],
    ) -> Option<Vec<u8>> {
        let mut h = [0u8; 16];
        self.encrypt_block(&mut h);

        let mut j0 = [0u8; 16];
        j0[..12].copy_from_slice(iv);
        j0[15] = 1;

        let mut computed_tag = ghash(&h, aad, ciphertext);
        let mut enc_j0 = j0;
        self.encrypt_block(&mut enc_j0);
        for i in 0..16 {
            computed_tag[i] ^= enc_j0[i];
        }

        let mut diff = 0u8;
        for i in 0..16 {
            diff |= computed_tag[i] ^ expected_tag[i];
        }
        if diff != 0 {
            return None;
        }

        let mut plaintext = Vec::with_capacity(ciphertext.len());
        let mut ctr = j0;
        for chunk in ciphertext.chunks(16) {
            inc32(&mut ctr);
            let mut keystream = ctr;
            self.encrypt_block(&mut keystream);
            for (i, &b) in chunk.iter().enumerate() {
                plaintext.push(b ^ keystream[i]);
            }
        }
        Some(plaintext)
    }
}

#[inline]
fn inc32(counter: &mut [u8; 16]) {
    let val = u32::from_be_bytes([counter[12], counter[13], counter[14], counter[15]]).wrapping_add(1);
    counter[12..16].copy_from_slice(&val.to_be_bytes());
}

fn ghash(h: &[u8; 16], aad: &[u8], ciphertext: &[u8]) -> [u8; 16] {
    let h_u128 = u128::from_be_bytes(*h);
    let mut y = 0u128;

    for chunk in aad.chunks(16) {
        let mut block = [0u8; 16];
        block[..chunk.len()].copy_from_slice(chunk);
        y = gf128_mul(y ^ u128::from_be_bytes(block), h_u128);
    }
    for chunk in ciphertext.chunks(16) {
        let mut block = [0u8; 16];
        block[..chunk.len()].copy_from_slice(chunk);
        y = gf128_mul(y ^ u128::from_be_bytes(block), h_u128);
    }

    let mut len_block = [0u8; 16];
    len_block[..8].copy_from_slice(&((aad.len() as u64) * 8).to_be_bytes());
    len_block[8..].copy_from_slice(&((ciphertext.len() as u64) * 8).to_be_bytes());
    y = gf128_mul(y ^ u128::from_be_bytes(len_block), h_u128);

    y.to_be_bytes()
}

/// ASN.1 DER length encoding helper
fn encode_der_tlv(tag: u8, content: &[u8]) -> Vec<u8> {
    let len = content.len();
    let mut out = Vec::with_capacity(4 + len);
    out.push(tag);
    if len < 0x80 {
        out.push(len as u8);
    } else if len <= 0xFF {
        out.push(0x81);
        out.push(len as u8);
    } else {
        out.push(0x82);
        out.push(((len >> 8) & 0xFF) as u8);
        out.push((len & 0xFF) as u8);
    }
    out.extend_from_slice(content);
    out
}

/// OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption) + NULL parameters
const SHA256_WITH_RSA_ALG_ID: [u8; 15] = [
    0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00,
];

fn encode_x500_cn_name(cn: &str) -> Vec<u8> {
    // OID 2.5.4.3 (commonName)
    let oid_cn = [0x06, 0x03, 0x55, 0x04, 0x03];
    let utf8_val = encode_der_tlv(0x0C, cn.as_bytes());
    let mut atv_body = Vec::with_capacity(oid_cn.len() + utf8_val.len());
    atv_body.extend_from_slice(&oid_cn);
    atv_body.extend_from_slice(&utf8_val);
    let atv_seq = encode_der_tlv(0x30, &atv_body);
    let rdn_set = encode_der_tlv(0x31, &atv_seq);
    encode_der_tlv(0x30, &rdn_set)
}

/// Builds RFC 5280 ASN.1 DER `TBSCertificate` for `CN=NVIDIA GameStream Client` (X.509v3).
pub fn build_tbs_certificate_der(spki_der: &[u8], serial_bytes: &[u8]) -> Vec<u8> {
    let mut body = Vec::with_capacity(256 + spki_der.len());

    // version: [0] EXPLICIT INTEGER 2 (v3)
    body.extend_from_slice(&[0xA0, 0x03, 0x02, 0x01, 0x02]);

    // serialNumber: positive ASN.1 INTEGER
    let mut serial = Vec::with_capacity(serial_bytes.len() + 1);
    let trimmed: &[u8] = {
        let mut s = serial_bytes;
        while s.len() > 1 && s[0] == 0 {
            s = &s[1..];
        }
        if s.is_empty() { &[1u8] } else { s }
    };
    if (trimmed[0] & 0x80) != 0 {
        serial.push(0x00);
    }
    serial.extend_from_slice(trimmed);
    body.extend_from_slice(&encode_der_tlv(0x02, &serial));

    // signature: AlgorithmIdentifier (sha256WithRSAEncryption)
    body.extend_from_slice(&SHA256_WITH_RSA_ALG_ID);

    // issuer: CN=NVIDIA GameStream Client
    let name_der = encode_x500_cn_name("NVIDIA GameStream Client");
    body.extend_from_slice(&name_der);

    // validity: UTCTime 240101000000Z .. 491231235959Z
    let not_before = encode_der_tlv(0x17, b"240101000000Z");
    let not_after = encode_der_tlv(0x17, b"491231235959Z");
    let mut validity_body = Vec::with_capacity(not_before.len() + not_after.len());
    validity_body.extend_from_slice(&not_before);
    validity_body.extend_from_slice(&not_after);
    body.extend_from_slice(&encode_der_tlv(0x30, &validity_body));

    // subject: CN=NVIDIA GameStream Client
    body.extend_from_slice(&name_der);

    // subjectPublicKeyInfo (already DER-encoded SPKI from standard RSA PublicKey.getEncoded())
    body.extend_from_slice(spki_der);

    encode_der_tlv(0x30, &body)
}

fn base64_encode(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(((data.len() + 2) / 3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;

        out.push(TABLE[((triple >> 18) & 0x3F) as usize] as char);
        out.push(TABLE[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[((triple >> 6) & 0x3F) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(triple & 0x3F) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

/// Wraps a signed `TBSCertificate` and RSA-SHA256 signature into a PEM-encoded X.509 certificate.
pub fn build_x509_certificate_pem(tbs_der: &[u8], signature_bytes: &[u8]) -> Vec<u8> {
    let mut bit_string_body = Vec::with_capacity(1 + signature_bytes.len());
    bit_string_body.push(0x00); // 0 unused bits
    bit_string_body.extend_from_slice(signature_bytes);
    let sig_bit_string = encode_der_tlv(0x03, &bit_string_body);

    let mut cert_body = Vec::with_capacity(
        tbs_der.len() + SHA256_WITH_RSA_ALG_ID.len() + sig_bit_string.len(),
    );
    cert_body.extend_from_slice(tbs_der);
    cert_body.extend_from_slice(&SHA256_WITH_RSA_ALG_ID);
    cert_body.extend_from_slice(&sig_bit_string);

    let cert_der = encode_der_tlv(0x30, &cert_body);
    let b64 = base64_encode(&cert_der);

    let mut pem = String::from("-----BEGIN CERTIFICATE-----\n");
    for chunk in b64.as_bytes().chunks(64) {
        if let Ok(line) = std::str::from_utf8(chunk) {
            pem.push_str(line);
            pem.push('\n');
        }
    }
    pem.push_str("-----END CERTIFICATE-----\n");
    pem.into_bytes()
}

/// Pure-Rust FIPS 180-4 SHA-256 hash implementation.
pub fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];

    let bit_len = (data.len() as u64) * 8;
    let mut padded = Vec::with_capacity(data.len() + 72);
    padded.extend_from_slice(data);
    padded.push(0x80);
    while (padded.len() % 64) != 56 {
        padded.push(0x00);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in padded.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                chunk[i * 4],
                chunk[i * 4 + 1],
                chunk[i * 4 + 2],
                chunk[i * 4 + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }

        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        let mut f = h[5];
        let mut g = h[6];
        let mut hh = h[7];

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    let mut out = [0u8; 32];
    for i in 0..8 {
        out[i * 4..i * 4 + 4].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

#[inline]
fn gf128_mul(x: u128, y: u128) -> u128 {
    let mut z = 0u128;
    let mut v = y;
    for i in 0..128 {
        let bit = (x >> (127 - i)) & 1;
        let bit_mask = 0u128.wrapping_sub(bit);
        z ^= v & bit_mask;
        let lsb = v & 1;
        let lsb_mask = 0u128.wrapping_sub(lsb);
        v = (v >> 1) ^ (0xe1000000_00000000_00000000_00000000u128 & lsb_mask);
    }
    z
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_aes128_fips197_vector() {
        let key = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        ];
        let mut block = [
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
        ];
        let expected = [
            0x69, 0xc4, 0xe0, 0xd8, 0x6a, 0x7b, 0x04, 0x30,
            0xd8, 0xcd, 0xb7, 0x80, 0x70, 0xb4, 0xc5, 0x5a,
        ];
        let cipher = Aes128::new(&key);
        cipher.encrypt_block(&mut block);
        assert_eq!(block, expected);
        cipher.decrypt_block(&mut block);
        assert_eq!(
            block,
            [
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
            ]
        );
    }

    #[test]
    fn test_aes128_gcm_roundtrip() {
        let key = [0x42u8; 16];
        let iv = [0x11u8; 12];
        let aad = b"vibemis-rtsp";
        let plaintext = b"ANNOUNCE rtsp://192.168.1.10:48010 RTSP/1.0";
        let cipher = Aes128::new(&key);
        let (ct, tag) = cipher.encrypt_gcm(&iv, aad, plaintext);
        let recovered = cipher.decrypt_gcm(&iv, aad, &ct, &tag).expect("GCM auth should succeed");
        assert_eq!(recovered, plaintext);
    }

    #[test]
    fn test_aes128_ecb_and_x509_pem() {
        let key = [0x24u8; 16];
        let cipher = Aes128::new(&key);
        let msg = [0xAAu8; 32];
        let enc = cipher.encrypt_ecb(&msg);
        let dec = cipher.decrypt_ecb(&enc);
        assert_eq!(dec, msg);

        let dummy_spki = [0x30, 0x05, 0x06, 0x03, 0x2A, 0x03, 0x04];
        let tbs = build_tbs_certificate_der(&dummy_spki, &[0x81, 0x23, 0x45]);
        assert_eq!(tbs[0], 0x30);
        let pem = build_x509_certificate_pem(&tbs, &[0xBBu8; 64]);
        let pem_str = String::from_utf8(pem).expect("UTF-8 PEM");
        assert!(pem_str.starts_with("-----BEGIN CERTIFICATE-----\n"));
        assert!(pem_str.ends_with("-----END CERTIFICATE-----\n"));
    }

    #[test]
    fn test_aes128_cbc_decrypt_into() {
        let key = [0x7Bu8; 16];
        let iv = [0x12u8; 16];
        let cipher = Aes128::new(&key);
        let msg = b"0123456789ABCDEFHello CBC World!";
        let ct = cipher.encrypt_cbc_pkcs7(&iv, msg);
        let alloc_dec = cipher.decrypt_cbc(&iv, &ct);
        let mut scratch = Vec::with_capacity(64);
        cipher.decrypt_cbc_into(&iv, &ct, &mut scratch);
        assert_eq!(&scratch[..msg.len()], msg);
        assert_eq!(scratch, alloc_dec);
    }
}
