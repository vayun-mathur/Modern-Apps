#[cfg(test)]
mod reconstruct_from_planet5 {
    //! The planet5 (~50 GB revision) archive drops the prebuilt junction layer (no release style
    //! draws it). This pins that the connectors stay reconstructable from what planet5 keeps: the
    //! junction builder is run over the build's own graph (the unthinned v7 `road_graph` output)
    //! and over planet5's graph (thinned to 1 m with the +-25 m junction zone, varint 1e-6 degree
    //! shape points, zstd blocks: v9), and every connector is matched.
    //!
    //! Env-gated (real graphs):
    //! `MAMAPS_JUNCTION_ORIG=<v7 graph dir> MAMAPS_JUNCTION_P5=<v9 graph dir> cargo test -p
    //! mamaps_build --release reconstruct_from_planet5 -- --ignored --nocapture`
    use super::*;

    type NodeConnectors = Vec<(u64, Vec<Vec<(f64, f64)>>)>;

    fn connectors(dir: &Path, stride: u64) -> NodeConnectors {
        let graph = Graph::load(dir).expect("graph");
        let lanes = LaneTable::load(dir).expect("lanes");
        let incoming = InEdges::build(&graph).expect("incoming");
        let conventions = Conventions::default();
        (0..graph.node_count)
            .step_by(stride as usize)
            .map(|n| (n, junctions_for_node(n, &graph, lanes.as_ref(), &incoming, &conventions)))
            .collect()
    }

    #[test]
    #[ignore = "needs MAMAPS_JUNCTION_ORIG and MAMAPS_JUNCTION_P5 (real graphs)"]
    fn connectors_reconstruct_from_the_planet5_graph() {
        let (Ok(a), Ok(b)) = (std::env::var("MAMAPS_JUNCTION_ORIG"), std::env::var("MAMAPS_JUNCTION_P5")) else {
            return;
        };
        let stride: u64 = std::env::var("MAMAPS_JUNCTION_STRIDE").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
        let ca = connectors(Path::new(&a), stride);
        let cb = connectors(Path::new(&b), stride);
        assert_eq!(ca.len(), cb.len(), "the two graphs must share node numbering");
        let (mut nodes, mut junction_nodes, mut same_count, mut conn_a, mut conn_b) = (0u64, 0u64, 0u64, 0u64, 0u64);
        let mut devs: Vec<f64> = Vec::new();
        for ((n, x), (m, y)) in ca.iter().zip(cb.iter()) {
            assert_eq!(n, m);
            nodes += 1;
            conn_a += x.len() as u64;
            conn_b += y.len() as u64;
            if x.is_empty() && y.is_empty() {
                continue;
            }
            junction_nodes += 1;
            if x.len() == y.len() {
                same_count += 1;
                for (p, q) in x.iter().zip(y.iter()) {
                    let mut d = 0.0f64;
                    if p.len() != q.len() {
                        d = f64::INFINITY;
                    } else {
                        for (u, v) in p.iter().zip(q.iter()) {
                            let dy = (u.1 - v.1) * 111_320.0;
                            let dx = (u.0 - v.0) * 111_320.0 * u.1.to_radians().cos();
                            d = d.max(dx.hypot(dy));
                        }
                    }
                    devs.push(d);
                }
            }
        }
        let inf = devs.iter().filter(|d| d.is_infinite()).count();
        devs.retain(|d| d.is_finite());
        devs.sort_by(|a, b| a.total_cmp(b));
        println!("connectors with a different sample count (shape changed, e.g. turn class or sweep bucket): {inf}");
        let pct = |q: f64| devs.get(((devs.len() as f64 - 1.0) * q) as usize).copied().unwrap_or(0.0);
        let exact = devs.iter().filter(|d| **d == 0.0).count();
        let within_10cm = devs.iter().filter(|d| **d <= 0.10).count();
        println!(
            "nodes {nodes} junction nodes {junction_nodes} (same connector count at {same_count}) | connectors orig {conn_a} p5 {conn_b} | \
             matched {} : exact {exact}, <=10 cm {within_10cm}, p50 {:.3} m p99 {:.3} m max {:.3} m",
            devs.len(),
            pct(0.5),
            pct(0.99),
            pct(1.0)
        );
        assert!(conn_a > 0, "no connectors built from the original graph");
        assert!(same_count as f64 >= junction_nodes as f64 * 0.999, "junction nodes whose connector count changed");
        // Measured on US West (v7 build graph vs planet5 v9): 99.93% of junction nodes keep their
        // connector count; of the connectors, 93% move <= 10 cm, p99 0.30 m; 1.5% change their
        // sampling, from approach headings that move <= ~2-3 degrees when 1 m thinning drops a
        // first interior point beyond the 25 m junction zone (or 1e-6 degree rounding meets a
        // very short first step). Every input is present; see the planet50 report for the
        // device-builder spec (headings over the first >= 5 m and arm length from `dist_mm`).
        assert!(inf as f64 <= conn_a as f64 * 0.03, "more than 3% of connectors changed sampling");
        assert!(within_10cm as f64 >= devs.len() as f64 * 0.9, "fewer than 90% of connectors within 10 cm");
        assert!(pct(0.5) <= 0.01, "median connector deviation above 1 cm");
    }
}
