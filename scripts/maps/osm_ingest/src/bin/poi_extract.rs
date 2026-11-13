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

    // The review table is parsed on its own thread while the PBF passes run;
    // `build_with_review_loader` joins it right before the reviews are applied.
    let loader = args.reviews.clone().map(|path| {
        std::thread::spawn(move || {
            let t = std::time::Instant::now();
            let table = osm_ingest::poi_reviews::ReviewTable::load(&path);
            eprintln!("[poi] review table loaded in {:.3}s", t.elapsed().as_secs_f64());
            table
        })
    });
    let have_reviews = loader.is_some();
    let on_loaded = |table: &osm_ingest::poi_reviews::ReviewTable| {
        println!("Loaded {} review row(s)", table.len());
    };

    let result = match loader {
        Some(loader) => osm_ingest::poi_build::build_with_review_loader(
            &args.input,
            &args.geojson,
            &args.names,
            &args.index,
            &args.attrs,
            &args.spatial,
            &args.name_index,
            &args.region,
            args.bbox,
            loader,
            &on_loaded,
        ),
        None => osm_ingest::poi_build::build_with_reviews(
            &args.input,
            &args.geojson,
            &args.names,
            &args.index,
            &args.attrs,
            &args.spatial,
            &args.name_index,
            &args.region,
            args.bbox,
            None,
        ),
    };
    match result {
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
            if have_reviews {
                println!("{} POI(s) with review data", stats.with_reviews);
            }
            println!(
                "{} populated grid cell(s), {} name index entr(ies)",
                stats.spatial_cells, stats.name_entries
            );
            println!("{} POI(s) with a boundary link", stats.with_region);
            println!(
                "Wrote {}, {}, {}, {}, {}, {}, {}, {}",
                args.geojson.display(),
                args.names.display(),
                args.index.display(),
                args.attrs.display(),
                args.spatial.display(),
                args.name_index.display(),
                args.region.display(),
                args.region.with_file_name("poi_admin.bin").display()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("ERROR: {e}");
            ExitCode::FAILURE
        }
    }
}
