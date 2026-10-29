//! `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE [--attrs FILE] [--reviews FILE]`
//!
//! Replaces `scripts/maps/poi_extract.cpp`. See `osm_ingest::poi_build` for the
//! POI type map and the on-disk record layout.

use std::process::ExitCode;

const USAGE: &str = "Usage: poi_extract IN.osm.pbf --geojson FILE --names FILE \
                     --index FILE [--attrs FILE] [--reviews FILE] [--region california|world] [--threads N]";

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let args = match osm_ingest::poi_build::parse_args(&argv) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("ERROR: {e}\n{USAGE}");
            return ExitCode::FAILURE;
        }
    };

    if let Some(n) = args.threads {
        osm_ingest::par::set_threads(n);
    }

    let reviews = match args.reviews.as_deref().map(osm_ingest::poi_reviews::ReviewTable::load) {
        Some(Ok(table)) => {
            println!("Loaded {} review row(s)", table.len());
            Some(table)
        }
        Some(Err(e)) => {
            eprintln!("ERROR: {e}");
            return ExitCode::FAILURE;
        }
        None => None,
    };

    match osm_ingest::poi_build::build_with_reviews(
        &args.input,
        &args.geojson,
        &args.names,
        &args.index,
        &args.attrs,
        &args.spatial,
        &args.name_index,
        args.bbox,
        reviews.as_ref(),
    ) {
        Ok(stats) => {
            println!(
                "{} POI(s) ({} node, {} closed-way, {} relation), {} unique name(s) \
                 ({} bytes)",
                stats.records,
                stats.from_nodes,
                stats.from_ways,
                stats.from_relations,
                stats.unique_names,
                stats.name_bytes,
            );
            println!(
                "{} POI(s) with attributes, {} unique record(s), {} sidecar byte(s)",
                stats.with_attrs, stats.unique_attrs, stats.attr_bytes
            );
            if reviews.is_some() {
                println!("{} POI(s) with review data", stats.with_reviews);
            }
            println!(
                "{} populated grid cell(s), {} name index entr(ies)",
                stats.spatial_cells, stats.name_entries
            );
            println!(
                "Wrote {}, {}, {}, {}, {}, {}",
                args.geojson.display(),
                args.names.display(),
                args.index.display(),
                args.attrs.display(),
                args.spatial.display(),
                args.name_index.display()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("ERROR: {e}");
            ExitCode::FAILURE
        }
    }
}
