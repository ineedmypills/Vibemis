use std::fs;
use std::sync::OnceLock;

const CPU_SETSIZE: usize = 1024;
const MASK_WORDS: usize = CPU_SETSIZE / (std::mem::size_of::<usize>() * 8);

#[repr(C)]
#[derive(Clone, Copy)]
struct CpuSet {
    bits: [usize; MASK_WORDS],
}

impl CpuSet {
    fn zero() -> Self {
        Self {
            bits: [0; MASK_WORDS],
        }
    }

    fn set(&mut self, cpu: usize) {
        if cpu < CPU_SETSIZE {
            let word_bits = std::mem::size_of::<usize>() * 8;
            self.bits[cpu / word_bits] |= 1usize << (cpu % word_bits);
        }
    }

    fn is_set(&self, cpu: usize) -> bool {
        if cpu < CPU_SETSIZE {
            let word_bits = std::mem::size_of::<usize>() * 8;
            (self.bits[cpu / word_bits] & (1usize << (cpu % word_bits))) != 0
        } else {
            false
        }
    }

    fn count(&self) -> u32 {
        self.bits.iter().map(|w| w.count_ones()).sum()
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
extern "C" {
    fn sched_setaffinity(pid: i32, cpusetsize: usize, mask: *const CpuSet) -> i32;
    fn sched_getaffinity(pid: i32, cpusetsize: usize, mask: *mut CpuSet) -> i32;
}

pub fn parse_cpu_list(s: &str) -> Vec<usize> {
    let mut out = Vec::new();
    let normalized = s.replace([',', '\t', '\n', '\r'], " ");
    for tok in normalized.split_whitespace() {
        if let Some((a_str, b_str)) = tok.split_once('-') {
            if let (Ok(a), Ok(b)) = (a_str.parse::<usize>(), b_str.parse::<usize>()) {
                let (lo, hi) = if a <= b { (a, b) } else { (b, a) };
                for cpu in lo..=hi {
                    if cpu < CPU_SETSIZE {
                        out.push(cpu);
                    }
                }
            }
        } else if let Ok(cpu) = tok.parse::<usize>() {
            if cpu < CPU_SETSIZE {
                out.push(cpu);
            }
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

fn read_online_cpus() -> Vec<usize> {
    if let Ok(content) = fs::read_to_string("/sys/devices/system/cpu/online") {
        let parsed = parse_cpu_list(&content);
        if !parsed.is_empty() {
            return parsed;
        }
    }
    let count = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(8)
        .min(CPU_SETSIZE);
    (0..count).collect()
}

fn read_cpu_max_freq_khz(cpu: usize) -> u64 {
    let paths = [
        format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/cpuinfo_max_freq"),
        format!("/sys/devices/system/cpu/cpu{cpu}/cpufreq/scaling_max_freq"),
    ];
    for p in &paths {
        if let Ok(txt) = fs::read_to_string(p) {
            if let Ok(val) = txt.trim().parse::<u64>() {
                if val > 0 {
                    return val;
                }
            }
        }
    }
    0
}

/// 3-tier CPU topology for big.LITTLE / DynamIQ SoCs.
pub struct CpuTopology {
    pub prime_cores: Vec<i32>,
    pub big_cores: Vec<i32>,
    pub little_cores: Vec<i32>,
    pub perf_cores: Vec<i32>, // big + prime
    pub all_cores: Vec<i32>,
}

/// Detects Prime, Big, and Little CPU clusters from sysfs max frequencies.
pub fn detect_topology() -> &'static CpuTopology {
    static TOPOLOGY: OnceLock<CpuTopology> = OnceLock::new();
    TOPOLOGY.get_or_init(|| {
        let online = read_online_cpus();
        let all_cores: Vec<i32> = online.iter().map(|&c| c as i32).collect();
        if online.len() <= 2 {
            return CpuTopology {
                prime_cores: Vec::new(),
                big_cores: all_cores.clone(),
                little_cores: Vec::new(),
                perf_cores: all_cores.clone(),
                all_cores,
            };
        }

        let freqs: Vec<(usize, u64)> = online
            .iter()
            .map(|&c| (c, read_cpu_max_freq_khz(c)))
            .collect();
        let max_freq = freqs.iter().map(|&(_, f)| f).max().unwrap_or(0);
        let min_freq = freqs
            .iter()
            .filter(|&&(_, f)| f > 0)
            .map(|&(_, f)| f)
            .min()
            .unwrap_or(0);

        if max_freq > 0 && min_freq > 0 && max_freq > min_freq {
            let mut distinct: Vec<u64> = freqs
                .iter()
                .map(|&(_, f)| f)
                .filter(|&f| f > 0)
                .collect();
            distinct.sort_unstable();
            distinct.dedup();

            let mut prime_cores = Vec::new();
            let mut big_cores = Vec::new();
            let mut little_cores = Vec::new();

            if distinct.len() >= 3 {
                // 3+ distinct frequency tiers: highest = Prime (if fewer than half the cores),
                // lowest = Little, middle = Big.
                let prime_thresh = max_freq.saturating_mul(95) / 100;
                for &(cpu, freq) in &freqs {
                    if freq >= prime_thresh {
                        prime_cores.push(cpu as i32);
                    } else if freq > min_freq {
                        big_cores.push(cpu as i32);
                    } else {
                        little_cores.push(cpu as i32);
                    }
                }
                if big_cores.is_empty() {
                    big_cores.append(&mut prime_cores);
                }
            } else {
                // 2 tiers: Little and Big
                for &(cpu, freq) in &freqs {
                    if freq > min_freq {
                        big_cores.push(cpu as i32);
                    } else {
                        little_cores.push(cpu as i32);
                    }
                }
            }

            let mut perf_cores = Vec::with_capacity(prime_cores.len() + big_cores.len());
            perf_cores.extend_from_slice(&big_cores);
            perf_cores.extend_from_slice(&prime_cores);
            perf_cores.sort_unstable();
            perf_cores.dedup();

            if !perf_cores.is_empty() {
                return CpuTopology {
                    prime_cores,
                    big_cores,
                    little_cores,
                    perf_cores,
                    all_cores,
                };
            }
        }

        // Fallback when cpufreq sysfs is unreadable: upper half is big, lower half is little.
        let half = online.len() / 2;
        let little_cores: Vec<i32> = online.iter().take(half).map(|&c| c as i32).collect();
        let big_cores: Vec<i32> = online.iter().skip(half).map(|&c| c as i32).collect();
        CpuTopology {
            prime_cores: Vec::new(),
            perf_cores: big_cores.clone(),
            big_cores,
            little_cores,
            all_cores,
        }
    })
}

pub fn get_prime_core_count() -> usize {
    detect_topology().prime_cores.len()
}

pub fn get_big_core_count() -> usize {
    detect_topology().big_cores.len()
}

pub fn get_little_core_count() -> usize {
    detect_topology().little_cores.len()
}

/// Automatically detects performance ("big" + "prime") CPU cores on big.LITTLE / DynamIQ SoCs.
pub fn detect_big_cores() -> &'static [i32] {
    &detect_topology().perf_cores
}

/// Pins the current worker thread to big/performance CPU cores for low-latency realtime streaming.
pub fn pin_current_thread_to_big_cores() {
    let cores = detect_big_cores();
    if !cores.is_empty() {
        set_current_thread_affinity(cores);
    }
}

/// Pins the current thread to a named core set ("all", "big", "prime", "little", "big_prime", "big_little", "prime_little").
pub fn pin_current_thread_to_core_set(core_set: &str) -> bool {
    let topo = detect_topology();
    let mut selected = Vec::new();
    match core_set {
        "prime" => selected.extend_from_slice(&topo.prime_cores),
        "big" => {
            if !topo.big_cores.is_empty() {
                selected.extend_from_slice(&topo.big_cores);
            } else {
                selected.extend_from_slice(&topo.perf_cores);
            }
        }
        "little" => selected.extend_from_slice(&topo.little_cores),
        "big_prime" => selected.extend_from_slice(&topo.perf_cores),
        "big_little" => {
            selected.extend_from_slice(&topo.big_cores);
            selected.extend_from_slice(&topo.little_cores);
        }
        "prime_little" => {
            selected.extend_from_slice(&topo.prime_cores);
            selected.extend_from_slice(&topo.little_cores);
        }
        _ => selected.extend_from_slice(&topo.all_cores),
    }

    if selected.is_empty() {
        selected.extend_from_slice(&topo.perf_cores);
    }
    if selected.is_empty() {
        selected.extend_from_slice(&topo.all_cores);
    }

    set_thread_affinity_tid(0, &selected)
}

/// Pins active rendering, codec, audio, and network threads in `/proc/self/task` to performance cores.
pub fn pin_hot_threads_to_big_cores() -> usize {
    let cores = detect_big_cores();
    if cores.is_empty() {
        return 0;
    }

    const HOT_PREFIXES: &[&str] = &[
        "GLThread",
        "GlUpscaler",
        "CCodec",
        "CodecLooper",
        "CodecAsync",
        "HwBinder",
        "RenderThread",
        "Video - Renderer",
        "Video - Choreo",
        "AudioTrack",
        "RustVideoRecv",
        "RustAudioRecv",
        "RustControlEnet",
    ];

    let mut pinned = 0usize;
    let Ok(entries) = fs::read_dir("/proc/self/task") else {
        return 0;
    };

    for entry in entries.flatten() {
        let Ok(tid) = entry.file_name().to_string_lossy().parse::<i32>() else {
            continue;
        };
        let comm_path = entry.path().join("comm");
        let Ok(comm) = fs::read_to_string(&comm_path) else {
            continue;
        };
        let trimmed = comm.trim();
        if HOT_PREFIXES.iter().any(|p| trimmed.starts_with(p) || trimmed.contains(p)) {
            if set_thread_affinity_tid(tid, cores) {
                pinned += 1;
            }
        }
    }

    pinned
}

pub fn set_current_thread_affinity(desired: &[i32]) {
    let _ = set_thread_affinity_tid(0, desired);
}

fn set_thread_affinity_tid(tid: i32, desired: &[i32]) -> bool {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    unsafe {
        let mut wanted = CpuSet::zero();
        for &c in desired {
            if c >= 0 {
                wanted.set(c as usize);
            }
        }
        let mut allowed = CpuSet::zero();
        let _ = sched_getaffinity(tid, std::mem::size_of::<CpuSet>(), &mut allowed);

        let mut online = CpuSet::zero();
        for c in read_online_cpus() {
            online.set(c);
        }

        let mut safe_mask = CpuSet::zero();
        let has_allowed = allowed.count() > 0;
        for c in 0..CPU_SETSIZE {
            if wanted.is_set(c) && online.is_set(c) && (!has_allowed || allowed.is_set(c)) {
                safe_mask.set(c);
            }
        }

        if safe_mask.count() > 0 {
            return sched_setaffinity(tid, std::mem::size_of::<CpuSet>(), &safe_mask) == 0;
        }
        false
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (tid, desired);
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_cpu_list() {
        assert_eq!(parse_cpu_list("0-3,6-7"), vec![0, 1, 2, 3, 6, 7]);
        assert_eq!(parse_cpu_list("0 2 4"), vec![0, 2, 4]);
    }

    #[test]
    fn test_detect_big_cores_non_empty() {
        let big = detect_big_cores();
        assert!(!big.is_empty());
        let topo = detect_topology();
        assert!(!topo.all_cores.is_empty());
        assert_eq!(
            topo.all_cores.len(),
            topo.prime_cores.len() + topo.big_cores.len() + topo.little_cores.len()
        );
    }
}
