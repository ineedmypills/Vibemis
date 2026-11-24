//! Pure Rust Reed-Solomon Erasure Coding over GF(2^8) (primitive polynomial 0x11d)
//! Pure Rust Reed-Solomon FEC erasure coding for Vibemis.

use std::sync::OnceLock;

struct GfTables {
    exp: [u8; 512],
    log: [u8; 256],
}

static GF_TABLES: OnceLock<GfTables> = OnceLock::new();

fn tables() -> &'static GfTables {
    GF_TABLES.get_or_init(|| {
        let mut exp = [0u8; 512];
        let mut log = [0u8; 256];
        let mut x = 1u16;
        for i in 0..255 {
            exp[i] = x as u8;
            exp[i + 255] = x as u8;
            log[x as usize] = i as u8;
            x <<= 1;
            if (x & 0x100) != 0 {
                x ^= 0x11d;
            }
        }
        exp[510] = exp[0];
        exp[511] = exp[1];
        GfTables { exp, log }
    })
}

#[inline]
pub fn gf_mul(a: u8, b: u8) -> u8 {
    if a == 0 || b == 0 {
        return 0;
    }
    let t = tables();
    let idx = t.log[a as usize] as usize + t.log[b as usize] as usize;
    t.exp[idx]
}

#[inline]
pub fn gf_inv(a: u8) -> Option<u8> {
    if a == 0 {
        return None;
    }
    let t = tables();
    Some(t.exp[255 - t.log[a as usize] as usize])
}

#[inline]
pub fn gf_div(a: u8, b: u8) -> Option<u8> {
    if b == 0 {
        return None;
    }
    if a == 0 {
        return Some(0);
    }
    let t = tables();
    let idx = 255 + t.log[a as usize] as usize - t.log[b as usize] as usize;
    Some(t.exp[idx])
}

#[inline]
fn gf_mul_nibble_tables(coeff: u8) -> ([u8; 16], [u8; 16]) {
    let mut lo = [0u8; 16];
    let mut hi = [0u8; 16];
    if coeff == 0 {
        return (lo, hi);
    }
    let t = tables();
    let log_c = t.log[coeff as usize] as usize;
    for i in 1..16usize {
        lo[i] = t.exp[log_c + t.log[i] as usize];
        hi[i] = t.exp[log_c + t.log[i << 4] as usize];
    }
    (lo, hi)
}

#[inline]
fn gf_mul_table(coeff: u8) -> [u8; 256] {
    let mut out = [0u8; 256];
    if coeff == 0 {
        return out;
    }
    let (lo, hi) = gf_mul_nibble_tables(coeff);
    for x in 0..256usize {
        out[x] = lo[x & 0x0F] ^ hi[x >> 4];
    }
    out
}

#[inline]
fn gf_mul_add_slice(dst: &mut [u8], src: &[u8], coeff: u8) {
    debug_assert_eq!(dst.len(), src.len());
    if coeff == 0 || dst.is_empty() {
        return;
    }
    if coeff == 1 {
        let mut i = 0;
        let len = dst.len();
        while i + 8 <= len {
            let d = u64::from_ne_bytes(dst[i..i + 8].try_into().unwrap());
            let s = u64::from_ne_bytes(src[i..i + 8].try_into().unwrap());
            dst[i..i + 8].copy_from_slice(&(d ^ s).to_ne_bytes());
            i += 8;
        }
        while i < len {
            dst[i] ^= src[i];
            i += 1;
        }
        return;
    }

    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: NEON (ASIMD) is mandatory on all aarch64 targets.
        unsafe {
            gf_mul_add_neon(dst, src, coeff);
        }
        return;
    }

    #[cfg(not(target_arch = "aarch64"))]
    {
        let tbl = gf_mul_table(coeff);
        let mut i = 0;
        let len = dst.len();
        while i + 4 <= len {
            dst[i] ^= tbl[src[i] as usize];
            dst[i + 1] ^= tbl[src[i + 1] as usize];
            dst[i + 2] ^= tbl[src[i + 2] as usize];
            dst[i + 3] ^= tbl[src[i + 3] as usize];
            i += 4;
        }
        while i < len {
            dst[i] ^= tbl[src[i] as usize];
            i += 1;
        }
    }
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn gf_mul_add_neon(dst: &mut [u8], src: &[u8], coeff: u8) {
    use std::arch::aarch64::*;
    let (lo, hi) = gf_mul_nibble_tables(coeff);
    let lo_vec = vld1q_u8(lo.as_ptr());
    let hi_vec = vld1q_u8(hi.as_ptr());
    let mask_0f = vdupq_n_u8(0x0F);

    let mut i = 0usize;
    let len = dst.len();
    while i + 16 <= len {
        let s = vld1q_u8(src.as_ptr().add(i));
        let d = vld1q_u8(dst.as_ptr().add(i));
        let s_lo = vandq_u8(s, mask_0f);
        let s_hi = vshrq_n_u8::<4>(s);
        let prod = veorq_u8(vqtbl1q_u8(lo_vec, s_lo), vqtbl1q_u8(hi_vec, s_hi));
        vst1q_u8(dst.as_mut_ptr().add(i), veorq_u8(d, prod));
        i += 16;
    }
    if i < len {
        let tbl = gf_mul_table(coeff);
        while i < len {
            dst[i] ^= tbl[src[i] as usize];
            i += 1;
        }
    }
}

pub struct ReedSolomon {
    pub data_shards: usize,
    pub parity_shards: usize,
    pub total_shards: usize,
    matrix: Vec<u8>,
}

impl ReedSolomon {
    pub fn new(data_shards: usize, parity_shards: usize) -> Option<Self> {
        let total_shards = data_shards.checked_add(parity_shards)?;
        if data_shards == 0 || total_shards > 255 {
            return None;
        }
        let mut matrix = vec![0u8; total_shards * data_shards];
        for r in 0..data_shards {
            matrix[r * data_shards + r] = 1;
        }
        // Cauchy parity rows: 1 / (x_i ^ y_j)
        for r in 0..parity_shards {
            let x = (data_shards + r) as u8;
            for c in 0..data_shards {
                let y = c as u8;
                matrix[(data_shards + r) * data_shards + c] = gf_inv(x ^ y)?;
            }
        }
        Some(Self {
            data_shards,
            parity_shards,
            total_shards,
            matrix,
        })
    }

    pub fn reconstruct(&self, shards: &mut [Vec<u8>], marks: &[bool]) -> bool {
        if shards.len() != self.total_shards || marks.len() != self.total_shards {
            return false;
        }
        let block_size = match shards.first() {
            Some(s) if !s.is_empty() => s.len(),
            _ => return false,
        };
        if !shards.iter().all(|s| s.len() == block_size) {
            return false;
        }

        let mut valid_indices = Vec::with_capacity(self.data_shards);
        for (i, &lost) in marks.iter().enumerate() {
            if !lost {
                valid_indices.push(i);
                if valid_indices.len() == self.data_shards {
                    break;
                }
            }
        }
        if valid_indices.len() < self.data_shards {
            return false;
        }

        let n = self.data_shards;
        let mut sub_matrix = vec![0u8; n * n];
        for (r, &shard_idx) in valid_indices.iter().enumerate() {
            sub_matrix[r * n..(r + 1) * n]
                .copy_from_slice(&self.matrix[shard_idx * n..(shard_idx + 1) * n]);
        }

        let inv_matrix = match invert_matrix(&mut sub_matrix, n) {
            Some(m) => m,
            None => return false,
        };

        // Reconstruct only lost data shards using snapshots of valid parity/data sources.
        let valid_shards: Vec<Vec<u8>> = valid_indices.iter().map(|&idx| shards[idx].clone()).collect();

        for i in 0..self.data_shards {
            if marks[i] {
                shards[i].fill(0);
                let row = &inv_matrix[i * n..(i + 1) * n];
                for (c, &coeff) in row.iter().enumerate() {
                    if coeff != 0 {
                        gf_mul_add_slice(&mut shards[i], &valid_shards[c], coeff);
                    }
                }
            }
        }
        true
    }

    pub fn encode_parity(&self, shards: &mut [Vec<u8>]) {
        if shards.len() < self.total_shards || self.data_shards == 0 {
            return;
        }
        let n = self.data_shards;
        let block_size = shards[0].len();
        if block_size == 0 || !shards[..n].iter().all(|s| s.len() == block_size) {
            return;
        }
        for p in 0..self.parity_shards {
            let row = &self.matrix[(n + p) * n..(n + p + 1) * n];
            let mut parity = vec![0u8; block_size];
            for c in 0..n {
                gf_mul_add_slice(&mut parity, &shards[c], row[c]);
            }
            shards[n + p] = parity;
        }
    }
}

fn invert_matrix(m: &mut [u8], n: usize) -> Option<Vec<u8>> {
    let mut inv = vec![0u8; n * n];
    for i in 0..n {
        inv[i * n + i] = 1;
    }

    for col in 0..n {
        if m[col * n + col] == 0 {
            let swap_row = (col + 1..n).find(|&r| m[r * n + col] != 0)?;
            for c in 0..n {
                m.swap(col * n + c, swap_row * n + c);
                inv.swap(col * n + c, swap_row * n + c);
            }
        }

        let pivot = m[col * n + col];
        let inv_pivot = gf_inv(pivot)?;
        for c in 0..n {
            m[col * n + c] = gf_mul(m[col * n + c], inv_pivot);
            inv[col * n + c] = gf_mul(inv[col * n + c], inv_pivot);
        }

        for r in 0..n {
            if r != col {
                let factor = m[r * n + col];
                if factor != 0 {
                    for c in 0..n {
                        m[r * n + c] ^= gf_mul(factor, m[col * n + c]);
                        inv[r * n + c] ^= gf_mul(factor, inv[col * n + c]);
                    }
                }
            }
        }
    }
    Some(inv)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_reed_solomon_recovery() {
        let rs = ReedSolomon::new(4, 2).unwrap();
        let mut shards = vec![
            vec![10, 20, 30, 40],
            vec![50, 60, 70, 80],
            vec![90, 100, 110, 120],
            vec![130, 140, 150, 160],
            vec![0; 4],
            vec![0; 4],
        ];
        rs.encode_parity(&mut shards);
        let orig_1 = shards[1].clone();
        let orig_3 = shards[3].clone();

        // Simulate losing data shards 1 and 3
        shards[1].fill(0);
        shards[3].fill(0);
        let marks = [false, true, false, true, false, false];
        assert!(rs.reconstruct(&mut shards, &marks));
        assert_eq!(shards[1], orig_1);
        assert_eq!(shards[3], orig_3);
    }

    #[test]
    fn test_gf_zero_and_malformed_inputs_do_not_panic() {
        assert_eq!(gf_inv(0), None);
        assert_eq!(gf_div(10, 0), None);
        assert_eq!(gf_div(0, 7), Some(0));

        let mut singular = vec![0u8, 0, 0, 0];
        assert!(invert_matrix(&mut singular, 2).is_none());

        let rs = ReedSolomon::new(4, 2).unwrap();
        let mut bad_shards = vec![vec![1, 2, 3], vec![4, 5], vec![6, 7, 8], vec![9, 10, 11], vec![0; 3], vec![0; 3]];
        let marks = [false, true, false, false, false, false];
        assert!(!rs.reconstruct(&mut bad_shards, &marks));
    }

    #[test]
    fn test_reed_solomon_large_block_recovery() {
        let rs = ReedSolomon::new(8, 3).unwrap();
        let mut shards: Vec<Vec<u8>> = (0..11)
            .map(|i| {
                if i < 8 {
                    (0..1024).map(|b| ((b * 13 + i * 37) & 0xFF) as u8).collect()
                } else {
                    vec![0u8; 1024]
                }
            })
            .collect();
        rs.encode_parity(&mut shards);
        let orig_0 = shards[0].clone();
        let orig_5 = shards[5].clone();
        let orig_7 = shards[7].clone();
        shards[0].fill(0);
        shards[5].fill(0);
        shards[7].fill(0);
        let marks = [true, false, false, false, false, true, false, true, false, false, false];
        assert!(rs.reconstruct(&mut shards, &marks));
        assert_eq!(shards[0], orig_0);
        assert_eq!(shards[5], orig_5);
        assert_eq!(shards[7], orig_7);
    }
}
