//! `wps_crawl --state DIR --out SHARD [options]`
//!
//! Expand a set of known BSSIDs into a beacon database by asking Apple's location service
//! where they are. Each response answers the query *and* returns a few hundred neighbouring
//! APs with their own coordinates, so the answers are also the next set of questions.
//!
//! ## Built to be interrupted
//!
//! A planet crawl runs for weeks. Everything that would be expensive to lose lives in
//! `--state`: the frontier queue, its cursor, and the approximate set of BSSIDs already seen.
//! State is checkpointed every `--checkpoint` requests and the output shard is append-only, so
//! killing the process is a supported way to stop it. Restarting with the same arguments picks
//! up from the last checkpoint; at worst the requests since then are repeated, and `wps_build`
//! deduplicates those anyway.
//!
//! ## Politeness is not optional
//!
//! This talks to somebody else's service, at length. Requests are serialized through one
//! client with a minimum interval between them, and errors back off rather than retrying hard.
//! Running it faster is both rude and self-defeating.

use std::fs::OpenOptions;
use std::io::{BufRead, BufWriter, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use wps_harvest::frontier::{Frontier, SeenSet};
use wps_harvest::gsloc::{self, Client};
use wps_harvest::keys;
use wps_harvest::probe::Prober;
use wps_harvest::progress::{FailKind, Progress};
use wps_harvest::quality::clamp_accuracy;
use wps_harvest::record::{Record, RecordWriter, Source};

/// Frontier checkpoints between seen-set checkpoints. The seen-set is rewritten whole, which
/// is gigabytes at planet scale, and losing it only costs repeated requests.
const SEEN_CHECKPOINT_EVERY: u32 = 25;

/// Stop after this many failures in a row. At the 300 s backoff ceiling that is over an hour
/// of being refused, which is not a blip.
const MAX_CONSECUTIVE_FAILURES: u32 = 20;

const USAGE: &str = "\
Usage: wps_crawl --state DIR --out SHARD [options]

  --state          directory holding the frontier, its cursor and the seen-set (resumable)
  --out            record shard to append to
  --seed FILE      newline-separated BSSIDs to enqueue before starting (repeatable)
  --probe N        when the frontier is empty, guess N random BSSIDs per request to find a
                   starting point (default 1; 0 disables). Learns which OUIs are productive
                   from every response, so the hit rate climbs as it runs.
  --oui-file FILE  OUIs to seed the guesser with: the IEEE registry, or any WiFi scan output.
                   Worth far more than guessing blind - see probe.rs.
  --probe-seed N   RNG seed for the guesser, so a run can be reproduced (default 1)
  --batch N        BSSIDs per request (default 1). Apple answers HTTP 400 to more than one,
                   so raising this stops the crawl working at all; it exists only so the
                   limit can be re-tested if the service changes.
  --interval MS    minimum milliseconds between requests (default 1000)
  --max-queries N  stop after N requests (default: until the frontier empties)
  --expected N     expected distinct BSSIDs, which sizes the seen-set (default 200000000)
  --checkpoint N   checkpoint state every N requests (default 200)
  --status-secs N  seconds between status lines (default 30). Timed rather than counted, so
                   a stalled crawl keeps reporting instead of going quiet.

On Windows, a free real seed:  netsh wlan show networks mode=bssid";

struct Args {
    state: PathBuf,
    out: PathBuf,
    seeds: Vec<PathBuf>,
    oui_files: Vec<PathBuf>,
    probe: usize,
    probe_seed: u64,
    batch: usize,
    interval: Duration,
    max_queries: Option<u64>,
    expected: u64,
    checkpoint_every: u64,
    status_secs: u64,
}

fn parse_args(argv: &[String]) -> Result<Args, String> {
    let mut state = None;
    let mut out = None;
    let mut seeds = Vec::new();
    let mut oui_files = Vec::new();
    let mut probe = 1usize;
    let mut probe_seed = 1u64;
    let mut batch = 1usize;
    let mut interval_ms = 1000u64;
    let mut max_queries = None;
    let mut expected = 200_000_000u64;
    let mut checkpoint_every = 200u64;
    let mut status_secs = 30u64;

    let mut i = 0;
    while i < argv.len() {
        let a = argv[i].as_str();
        let value = || argv.get(i + 1).cloned().ok_or_else(|| format!("{a} needs a value"));
        match a {
            "--state" => {
                state = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--out" => {
                out = Some(PathBuf::from(value()?));
                i += 2;
            }
            "--seed" => {
                seeds.push(PathBuf::from(value()?));
                i += 2;
            }
            "--oui-file" => {
                oui_files.push(PathBuf::from(value()?));
                i += 2;
            }
            "--probe" => {
                probe = value()?.parse().map_err(|_| "--probe must be a number")?;
                i += 2;
            }
            "--probe-seed" => {
                probe_seed = value()?.parse().map_err(|_| "--probe-seed must be a number")?;
                i += 2;
            }
            "--batch" => {
                batch = value()?.parse().map_err(|_| "--batch must be a number")?;
                i += 2;
            }
            "--interval" => {
                interval_ms = value()?.parse().map_err(|_| "--interval must be a number")?;
                i += 2;
            }
            "--max-queries" => {
                max_queries = Some(value()?.parse().map_err(|_| "--max-queries must be a number")?);
                i += 2;
            }
            "--expected" => {
                expected = value()?.parse().map_err(|_| "--expected must be a number")?;
                i += 2;
            }
            "--checkpoint" => {
                checkpoint_every = value()?.parse().map_err(|_| "--checkpoint must be a number")?;
                i += 2;
            }
            "--status-secs" => {
                status_secs = value()?.parse().map_err(|_| "--status-secs must be a number")?;
                i += 2;
            }
            "-h" | "--help" => return Err("help".to_string()),
            other => return Err(format!("unknown option: {other}")),
        }
    }
    Ok(Args {
        state: state.ok_or("--state is required")?,
        out: out.ok_or("--out is required")?,
        seeds,
        oui_files,
        probe,
        probe_seed,
        batch: batch.max(1),
        interval: Duration::from_millis(interval_ms),
        max_queries,
        expected,
        checkpoint_every: checkpoint_every.max(1),
        status_secs: status_secs.max(1),
    })
}

fn to_record(fix: &gsloc::WifiFix) -> Option<Record> {
    let mac = keys::parse_mac(&fix.bssid)?;
    // Randomized and multicast addresses are not places. Dropping them at the source keeps
    // them out of the frontier as well as out of the shard.
    if keys::is_randomized_mac(mac) {
        return None;
    }
    let r = Record {
        key: keys::wifi_key(mac),
        lat_e8: fix.lat_e8,
        lon_e8: fix.lon_e8,
        accuracy_m: clamp_accuracy(fix.accuracy_m),
        altitude_m: fix.altitude_m,
        source: Source::Gsloc,
    };
    r.in_range().then_some(r)
}

fn run(args: &Args) -> std::io::Result<()> {
    std::fs::create_dir_all(&args.state)?;
    let mut frontier = Frontier::open(&args.state.join("frontier.bin"))?;
    let mut seen = SeenSet::open(&args.state.join("seen.bin"), args.expected)?;

    for seed_path in &args.seeds {
        let file = std::io::BufReader::new(std::fs::File::open(seed_path)?);
        let mut added = 0u64;
        for line in file.lines() {
            let line = line?;
            let Some(mac) = keys::parse_mac(line.trim().to_ascii_lowercase().as_str()) else {
                continue;
            };
            if keys::is_randomized_mac(mac) || !seen.insert(mac) {
                continue;
            }
            frontier.push(mac)?;
            added += 1;
        }
        println!("Seeded {added} BSSID(s) from {}", seed_path.display());
    }
    frontier.checkpoint()?;
    seen.checkpoint()?;

    let mut prober = Prober::new(args.probe_seed);
    for path in &args.oui_files {
        let text = std::fs::read_to_string(path)?;
        let added = prober.load_ouis(&text);
        println!("Loaded {added} OUI(s) from {}", path.display());
    }

    if frontier.pending() == 0 && args.probe == 0 {
        eprintln!(
            "error: nothing to crawl and --probe 0. Seed the frontier with --seed, or allow \
             random probing."
        );
        return Ok(());
    }
    if frontier.pending() == 0 {
        println!(
            "Frontier empty: guessing {} BSSID(s) per request until one lands. {} OUI(s) known.",
            args.probe,
            prober.known_ouis()
        );
    }

    // Append rather than truncate: a resumed crawl adds to the same shard, and `wps_build`
    // deduplicates. Truncating would silently throw away the previous run's work.
    let out = OpenOptions::new().create(true).append(true).open(&args.out)?;
    let mut writer = RecordWriter::new(BufWriter::with_capacity(1 << 20, out));
    let mut client = Client::new(args.interval);

    let mut progress = Progress::new(Duration::from_secs(args.status_secs));
    let mut backoff = args.interval;
    let mut since_seen_checkpoint = 0u32;
    let mut consecutive_failures = 0u32;

    loop {
        if args.max_queries.is_some_and(|m| progress.queries >= m) {
            println!("Reached --max-queries {}", progress.queries);
            break;
        }
        // Counted separately from successes: without this a crawl whose requests all fail
        // loops forever, doubling its backoff and never reaching any stopping condition.
        if args.max_queries.is_some_and(|m| progress.attempts >= m.saturating_mul(4) + 10) {
            eprintln!(
                "error: giving up after {} attempt(s) for {} success(es)",
                progress.attempts, progress.queries
            );
            break;
        }
        if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
            eprintln!(
                "error: {consecutive_failures} consecutive failures; stopping rather than \
                 hammering a service that is clearly refusing us"
            );
            break;
        }
        // Real frontier entries always come first; guessing is only for the cold start, and
        // stops as soon as a response gives the crawl somewhere real to go.
        let batch = frontier.take(args.batch)?;
        let probing = batch.is_empty();
        let batch = if probing {
            if args.probe == 0 {
                println!("Frontier exhausted after {} quer(ies)", progress.queries);
                break;
            }
            prober.candidates(args.probe)
        } else {
            batch
        };
        let bssids: Vec<String> = batch.iter().map(|&m| format_mac(m)).collect();

        match client.query_wifi(&bssids) {
            Ok(fixes) => {
                backoff = args.interval;
                consecutive_failures = 0;
                if probing && !fixes.is_empty() {
                    prober.hits += 1;
                    println!(
                        "Probe landed after {} guess(es): {} result(s), hit rate {:.4}%",
                        prober.probes,
                        fixes.len(),
                        prober.hit_rate() * 100.0
                    );
                }
                let (mut observations, mut discovered) = (0u64, 0u64);
                for fix in &fixes {
                    let Some(record) = to_record(fix) else { continue };
                    writer.push(&record)?;
                    observations += 1;
                    // Every real address teaches the guesser which vendors exist, so the cold
                    // start gets cheaper even while the frontier is doing the work.
                    let mac = record.key as u64;
                    let _ = prober.observe(mac);
                    // Only unseen neighbours extend the frontier; the seen-set is what keeps
                    // the crawl from walking in circles.
                    if seen.insert(mac) {
                        frontier.push(mac)?;
                        discovered += 1;
                    }
                }
                progress.success(observations, discovered);
            }
            Err(e) => {
                let kind = match &e {
                    gsloc::Error::Status(400) => FailKind::BadRequest,
                    gsloc::Error::Status(_) => FailKind::Status,
                    gsloc::Error::Transport(_) => FailKind::Transport,
                    gsloc::Error::Malformed(_) => FailKind::Malformed,
                };
                progress.failure(kind);
                consecutive_failures += 1;
                // Back off geometrically to a ceiling. A refusal usually means "slow down",
                // and hammering through it would make the crawl worse, not faster.
                backoff = (backoff * 2).min(Duration::from_secs(300));
                eprintln!(
                    "query failed ({e}) on {} BSSID(s){}; backing off {:?}",
                    bssids.len(),
                    if probing { " (probe)" } else { "" },
                    backoff
                );
                std::thread::sleep(backoff);
            }
        }

        // Checkpointing is tied to query count; status output is tied to the clock, so a
        // crawl that has stalled keeps saying so instead of going quiet.
        if progress.queries > 0 && progress.queries % args.checkpoint_every == 0 {
            // Flush the shard first: records that were never flushed are lost outright,
            // whereas a stale frontier cursor only costs a few repeated requests.
            writer.flush()?;
            frontier.checkpoint()?;
            // The seen-set is a whole-file rewrite — 2 GB at --expected 1e9 — so it is
            // checkpointed far less often than the frontier. Losing it costs re-queries of
            // BSSIDs already visited, not data, which is worth trading for not writing
            // gigabytes every few minutes.
            since_seen_checkpoint += 1;
            if since_seen_checkpoint >= SEEN_CHECKPOINT_EVERY {
                seen.checkpoint()?;
                since_seen_checkpoint = 0;
            }
        }

        if progress.should_print() {
            println!("{}", progress.line(frontier.pending(), seen.false_positive_rate()));
            if prober.probes > 0 {
                println!(
                    "    probes {}, hits {}, rate {:.4}%, {} OUI(s) known",
                    prober.probes,
                    prober.hits,
                    prober.hit_rate() * 100.0,
                    prober.known_ouis()
                );
            }
            progress.reset_window();
            // Nothing downstream reads this until the process exits, and a crawl is normally
            // watched through a redirected log, so keep the file current.
            let _ = std::io::stdout().flush();
        }
    }

    let _ = writer.finish()?;
    frontier.checkpoint()?;
    seen.checkpoint()?;

    println!("{}", progress.summary(frontier.pending()));
    println!("Resume with the same --state to continue (drop --seed; the frontier persists).");
    Ok(())
}

/// Render a 48-bit key back to the `aa:bb:cc:dd:ee:ff` form the protocol sends.
fn format_mac(mac: u64) -> String {
    let b = mac.to_be_bytes();
    format!("{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}", b[2], b[3], b[4], b[5], b[6], b[7])
}

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match parse_args(&argv) {
        Ok(a) => a,
        Err(e) => {
            if e != "help" {
                eprintln!("error: {e}");
            }
            eprintln!("{USAGE}");
            return if e == "help" { ExitCode::SUCCESS } else { ExitCode::FAILURE };
        }
    };
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macs_round_trip_through_the_wire_format() {
        for s in ["00:11:22:33:44:55", "aa:bb:cc:dd:ee:ff", "00:00:00:00:00:01"] {
            let mac = keys::parse_mac(s).unwrap();
            assert_eq!(format_mac(mac), s);
        }
    }

    #[test]
    fn randomized_and_out_of_range_fixes_never_enter_the_shard() {
        let good = gsloc::WifiFix {
            bssid: "00:11:22:33:44:55".to_string(),
            lat_e8: 37_77493000,
            lon_e8: -122_41942000,
            accuracy_m: 35,
            altitude_m: 44,
        };
        assert_eq!(to_record(&good).unwrap().accuracy_m, 35);
        assert_eq!(to_record(&good).unwrap().altitude_m, 44);

        let randomized = gsloc::WifiFix { bssid: "aa:bb:cc:dd:ee:ff".to_string(), ..good.clone() };
        assert_eq!(to_record(&randomized), None);

        let out_of_range = gsloc::WifiFix { lat_e8: 91_00000000, ..good.clone() };
        assert_eq!(to_record(&out_of_range), None);

        let unparseable = gsloc::WifiFix { bssid: "nonsense".to_string(), ..good.clone() };
        assert_eq!(to_record(&unparseable), None);
    }

    #[test]
    fn an_absurd_accuracy_saturates_rather_than_wrapping() {
        let fix = gsloc::WifiFix {
            bssid: "00:11:22:33:44:55".to_string(),
            lat_e8: 0,
            lon_e8: 0,
            accuracy_m: 10_000_000,
            altitude_m: 0,
        };
        assert_eq!(to_record(&fix).unwrap().accuracy_m, 65534);
    }
}
