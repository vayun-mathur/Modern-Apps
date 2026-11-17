//! Conv geometry inventory for the vision nets, on the host (no device needed).
//!
//! ```text
//! cargo run --offline -p modelrunner --example dump_conv_shapes -- path/to/assets
//! ```
//!
//! Prints every `Kind::Conv` dispatch's geometry and MACs, hottest first, so the
//! tiled spatial-conv work can pick tile sizes against the real shapes.
use modelrunner::nets::{
    maia, mobilefacenet, modnet, ppocr_det, ppocr_rec, scrfd, selfie, tinyclip, u2netp, Kind,
    Op, Plan,
};
use modelrunner::post::ocr::REC_WIDTH;
use modelrunner::weights::{graph, Weights};

fn main() {
    let dir = std::env::args()
        .nth(1)
        .or_else(|| std::env::var("MODELRUNNER_VISION").ok())
        .unwrap_or_else(|| ".".into());
    let mut plans: Vec<(String, Plan)> = Vec::new();
    let mut open = |name: &str, file: &str, graph_id: u32, build: &dyn Fn(&Weights) -> Result<Plan, String>| {
        let path = format!("{dir}/{file}");
        match std::fs::read(&path) {
            Ok(bytes) => match Weights::parse(&bytes, graph_id) {
                Ok(w) => match build(&w) {
                    Ok(plan) => plans.push((name.to_string(), plan)),
                    Err(e) => println!("{name}: build failed: {e}"),
                },
                Err(e) => println!("{name}: parse failed: {e}"),
            },
            Err(e) => println!("{name}: cannot read {path}: {e}"),
        }
    };
    open("selfie", "selfie_segmentation.maml", graph::SELFIE, &|w| selfie::build(w));
    open("modnet", "modnet.maml", graph::MODNET, &|w| modnet::build(w));
    open("u2netp", "u2netp.maml", graph::U2NETP, &|w| u2netp::build(w));
    open("scrfd", "scrfd_500m.maml", graph::SCRFD, &|w| {
        scrfd::build(w, scrfd::LONG_SIDE, scrfd::LONG_SIDE)
    });
    open("mbf", "w600k_mbf.maml", graph::MOBILEFACENET, &|w| mobilefacenet::build(w));
    open("det", "ppocr_det.maml", graph::PPOCR_DET, &|w| {
        ppocr_det::build(w, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)
    });
    open("rec", "ppocr_rec.maml", graph::PPOCR_REC, &|w| ppocr_rec::build(w, REC_WIDTH));
    open("clip_image", "clip/tinyclip.maml", graph::TINYCLIP, &|w| {
        tinyclip::build(w, tinyclip::Mode::Image)
    });
    open("maia", "maia3-5m.maml", graph::MAIA, &|w| maia::build(w));

    // (net, step, out_c, out_h, out_w, in_c, kh, kw, stride, dil, group, act, gmac)
    type ConvRow = (String, usize, u32, u32, u32, u32, u32, u32, u32, u32, u32, u32, f64);
    let mut rows: Vec<ConvRow> = Vec::new();
    for (name, plan) in &plans {
        for (step, op) in plan.ops.iter().enumerate() {
            let Op::Dispatch { kind: Kind::Conv, push, .. } = op else { continue };
            let macs = push.out_c as u64 * push.out_h as u64 * push.out_w as u64
                * (push.in_c as u64 / push.group as u64)
                * push.kh as u64
                * push.kw as u64;
            rows.push((
                name.to_string(),
                step,
                push.out_c,
                push.out_h,
                push.out_w,
                push.in_c,
                push.kh,
                push.kw,
                push.stride_h,
                push.dil_h,
                push.group,
                push.act,
                macs as f64 / 1e9,
            ));
        }
    }
    rows.sort_by(|a, b| b.12.total_cmp(&a.12));
    println!(
        "  {:<10} {:>4} {:>9} {:>11} {:>9} {:>3} {:>2} {:>2} {:>3} {:>7}",
        "net", "step", "out_cxhxw", "in_c", "kxk", "s", "d", "g", "act", "GMAC"
    );
    let mut total = 0.0;
    for (name, step, oc, oh, ow, ic, kh, kw, s, d, g, act, gmac) in &rows {
        println!(
            "  {name:<10} {step:>4} {oc:>4}x{oh:<4}x{ow:<4} {ic:>6} {kh:>2}x{kw:<2} {s:>2} {d:>2} {g:>4} {act:>3} {gmac:>7.3}",
        );
        total += gmac;
    }
    println!("total untiled Conv GMAC: {total:.2}");
    // Per-net dense (group==1) vs grouped/depthwise GMAC, for ranking the work.
    // rows entries: (name, step, oc, oh, ow, ic, kh, kw, s, d, g, act, gmac)
    let mut per_net: std::collections::BTreeMap<String, (f64, f64)> =
        std::collections::BTreeMap::new();
    for r in &rows {
        let entry = per_net.entry(r.0.clone()).or_default();
        if r.10 == 1 {
            entry.0 += r.12;
        } else {
            entry.1 += r.12;
        }
    }
    println!("per net (dense, grouped) GMAC:");
    for (name, (dense, grouped)) in &per_net {
        println!("  {name:<10} dense {dense:>7.3} grouped {grouped:>7.3}");
    }
}
