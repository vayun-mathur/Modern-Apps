//! The POI kind filter and the shared toggle snapshot read by the tile workers.

use super::{Layer, LayerToggles, Toggle};

/// Which POI kinds the map is narrowed to, or empty for "every kind the style draws".
///
/// The app's category chips (Food, Gas, Hotels, …) select a handful of kinds out of the six
/// `poi-*` layers' thirty-nine. That is a *sub-layer* filter, so it cannot be expressed by
/// turning layers off, and it cannot ride in [`SharedToggles`]' packed word either — there are
/// more kinds than a `u32` has bits.
///
/// Interned ids rather than names, sorted, so the per-feature test is a binary search over a
/// `u16` slice — the same shape [`Layer::matches_id`] already uses for the layer's own whitelist.
/// An `Arc` because a worker holds it for the length of a tile build while the host may replace it.
#[derive(Clone, Debug, Default)]
pub struct KindFilter(std::sync::Arc<[u16]>);

impl PartialEq for KindFilter {
    fn eq(&self, other: &KindFilter) -> bool {
        // By value, not by pointer: the host rebuilds this list on every recomposition, so a
        // pointer comparison would report a change on every frame and re-tessellate forever.
        self.0[..] == other.0[..]
    }
}

impl Eq for KindFilter {}

impl KindFilter {
    /// Every kind. The default, and what an empty chip selection means.
    pub fn all() -> KindFilter {
        KindFilter(std::sync::Arc::from(Vec::new()))
    }

    /// A filter over interned kind ids. Sorted and deduplicated here so callers need not be.
    pub fn new(mut kinds: Vec<u16>) -> KindFilter {
        kinds.sort_unstable();
        kinds.dedup();
        KindFilter(std::sync::Arc::from(kinds))
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Does this filter admit `kind` on `layer`?
    ///
    /// Only POI layers are filtered. The chips narrow which *points of interest* are shown; a
    /// road or a country label is not one, and hiding the basemap because the user tapped
    /// "Coffee" would be absurd.
    pub fn admits(&self, layer: &Layer, kind: u16) -> bool {
        if self.0.is_empty() || layer.toggle != Some(Toggle::Poi) {
            return true;
        }
        self.0.binary_search(&kind).is_ok()
    }

    /// The subset of `source_kinds` this filter admits, sorted.
    ///
    /// The kinds-aware re-tessellation check ([`ToggleStamp::stale_under`]): a filter
    /// change rebuilds a tile only when the admitted set changed. An empty filter
    /// admits everything, so the answer is the source set itself.
    pub fn admitted(&self, source_kinds: &[u16]) -> Vec<u16> {
        if self.0.is_empty() {
            let mut out = source_kinds.to_vec();
            out.sort_unstable();
            out.dedup();
            return out;
        }
        let mut out: Vec<u16> = source_kinds
            .iter()
            .copied()
            .filter(|k| self.0.binary_search(k).is_ok())
            .collect();
        out.sort_unstable();
        out.dedup();
        out
    }
}

/// Which toggle-gated content a tile's source could emit, independent of the snapshot it
/// was built at.
///
/// Tessellation gates optional layers, so a mesh does not record what was gated *out* —
/// and without that, any toggle flip must rebuild every tile, because a tile built with
/// POI off might gain POIs when it turns on. This summary closes that gap: it is computed
/// from the source at build time (see `tile::geometry::build_toggled`), so `has_tile` can
/// prove a toggle change affects nothing here and skip the rebuild.
///
/// Only toggle-gated layers feed it. Basemap content is never gated, so no summary could
/// ever excuse it — and `matches_feature` over POI/transit/traffic candidates is integer
/// compares, not shaping, so recording this costs a fraction of what the gate saves.
#[derive(Clone, Debug, Default)]
pub struct GatedSummary {
    /// Distinct interned `kind`s of the source features a POI-toggle layer admits
    /// ([`Layer::matches_feature`]), before the category filter. The admitted set under a
    /// filter is [`KindFilter::admitted`] over this; a filter change rebuilds the tile
    /// only when that set changed.
    pub poi_kinds: Vec<u16>,
    /// A transit-toggle layer admits at least one source feature.
    pub has_transit: bool,
    /// The tile carries live-traffic segments (see `tile::geometry::traffic`).
    pub has_traffic: bool,
    /// A safety-toggle layer admits at least one source feature.
    pub has_safety: bool,
}

impl GatedSummary {
    /// The POI kinds this filter admits out of the source set, sorted.
    pub fn admitted(&self, filter: &KindFilter) -> Vec<u16> {
        filter.admitted(&self.poi_kinds)
    }
}

/// The toggle snapshot a mesh was built at, plus what its source could have emitted.
///
/// Stamp and summary travel together on every [`crate::tile::geometry::TileMesh`] so the
/// render thread can answer freshness without the source: [`stale_under`](Self::stale_under)
/// is the kinds-aware re-tessellation predicate. The global `generation` counter is kept
/// unchanged (it is the fast path and the tested contract); this is the second opinion
/// consulted when the generation has moved on.
#[derive(Clone, Debug)]
pub struct ToggleStamp {
    /// The toggles the mesh was built with.
    pub toggles: LayerToggles,
    /// The kind filter the mesh was built with.
    pub kinds: KindFilter,
    /// What the source could have emitted.
    pub gated: GatedSummary,
}

impl ToggleStamp {
    /// Would this mesh's content differ under `toggles` + `kinds`?
    ///
    /// `false` means rebuilding is provably useless: every gate that admits a feature of
    /// this tile agrees, so the rebuild would emit bit-identical geometry. `true` means
    /// rebuild (it is conservative — a changed gate with no geometric effect still
    /// answers true when the summary cannot prove otherwise).
    ///
    /// The cases, in order:
    /// * transit/traffic flip with source content behind the gate: the layer appears or
    ///   vanishes, so rebuild;
    /// * POI flip: turning off rebuilds only when something is shown (admitted under the
    ///   built filter), turning on rebuilds only when the source holds POI features;
    /// * kind-filter change with POI drawn: rebuilds only when the admitted set changed,
    ///   so narrowing Food→Coffee skips every tile with no food POIs, and widening the
    ///   chips skips every tile whose POIs were already all admitted.
    ///
    /// A filter change with POI off is always fresh: POI layers are gated regardless of
    /// the filter, so the meshes cannot differ.
    pub fn stale_under(&self, toggles: &LayerToggles, kinds: &KindFilter) -> bool {
        let built = &self.toggles;
        if built.transit != toggles.transit && self.gated.has_transit {
            return true;
        }
        if built.traffic != toggles.traffic && self.gated.has_traffic {
            return true;
        }
        if built.safety != toggles.safety && self.gated.has_safety {
            return true;
        }
        if built.poi != toggles.poi {
            if built.poi {
                // Was on: turning off removes what is shown.
                if !self.gated.admitted(&self.kinds).is_empty() {
                    return true;
                }
            } else if !self.gated.poi_kinds.is_empty() {
                // Was off: turning on adds whatever the source holds.
                return true;
            }
        }
        if built.poi
            && self.kinds != *kinds
            && self.gated.admitted(&self.kinds) != self.gated.admitted(kinds)
        {
            return true;
        }
        false
    }
}

/// The toggles, the POI kind filter and a generation counter, read as one snapshot and shared
/// with the tile workers.
///
/// Gating happens at **tessellation** time rather than at draw time, because that is
/// where the cost is: with POI off, no label is shaped and no placement candidate is
/// built for any resident tile. The price of gating there is that a toggle change
/// invalidates meshes, so every mesh records the generation it was built at and the
/// renderer re-requests anything stale. Re-tessellation goes through the existing worker
/// pool and reads the archive it already has, so nothing is refetched and nothing is
/// evicted — the same "re-decorate without reloading" shape as a palette switch, one step
/// heavier.
///
/// One snapshot rather than a field each so a worker's read is consistent: a mesh tagged with
/// one generation but built from another's flags would either never be refreshed or be refreshed
/// forever. This was a single `AtomicU32` while the state was two bits and a counter; the kind
/// filter does not fit in a word, so it is a lock — taken once per tile build, not per feature.
#[derive(Debug)]
pub struct SharedToggles(std::sync::RwLock<Snapshot>);

#[derive(Clone, Debug)]
struct Snapshot {
    toggles: LayerToggles,
    kinds: KindFilter,
    generation: u32,
}

impl SharedToggles {
    /// Generations start at 1, so a mesh from a default-initialised 0 is always stale.
    pub fn new(toggles: LayerToggles) -> SharedToggles {
        SharedToggles(std::sync::RwLock::new(Snapshot {
            toggles,
            kinds: KindFilter::all(),
            generation: 1,
        }))
    }

    /// The current toggles, kind filter and the generation they belong to.
    pub fn get(&self) -> (LayerToggles, KindFilter, u32) {
        // A poisoned lock means another thread panicked mid-set. The state behind it is three
        // plain values with no invariant between them beyond the generation, so reading it is
        // sound; refusing to draw the map because a worker died is worse than drawing it.
        let snapshot = self.0.read().unwrap_or_else(|e| e.into_inner());
        (
            snapshot.toggles,
            snapshot.kinds.clone(),
            snapshot.generation,
        )
    }

    /// Set the toggles and the kind filter, bumping the generation. Returns `true` when
    /// anything changed.
    ///
    /// Both at once rather than one setter each: the host sets them from a single Compose
    /// effect, and two bumps would re-tessellate the whole resident set twice for one change.
    ///
    /// A no-op set must not bump: the host may call this on every recomposition, and a
    /// bump there would re-tessellate the whole resident set for nothing.
    pub fn set(&self, toggles: LayerToggles, kinds: KindFilter) -> bool {
        let mut snapshot = self.0.write().unwrap_or_else(|e| e.into_inner());
        if snapshot.toggles == toggles && snapshot.kinds == kinds {
            return false;
        }
        snapshot.toggles = toggles;
        snapshot.kinds = kinds;
        snapshot.generation = snapshot.generation.wrapping_add(1).max(1);
        true
    }
}

#[cfg(test)]
mod stamp_tests {
    use super::*;
    use crate::style::kind_id;

    fn kinds(names: &[&str]) -> KindFilter {
        KindFilter::new(names.iter().filter_map(|n| kind_id(n)).collect())
    }

    fn stamp(
        poi: bool,
        transit: bool,
        traffic: bool,
        filter: KindFilter,
        poi_kinds: Vec<u16>,
        has_transit: bool,
        has_traffic: bool,
    ) -> ToggleStamp {
        ToggleStamp {
            toggles: LayerToggles {
                poi,
                transit,
                traffic,
                safety: false,
            },
            kinds: filter,
            gated: GatedSummary {
                poi_kinds,
                has_transit,
                has_traffic,
                has_safety: false,
            },
        }
    }

    fn cafe_bar() -> (u16, u16) {
        (
            kind_id("cafe").expect("cafe"),
            kind_id("bar").expect("bar"),
        )
    }

    /// The common case this exists for: tapping a chip must not rebuild a tile with no
    /// POIs, and must not rebuild one whose POIs are all outside both the old and the
    /// new filter.
    #[test]
    fn a_kind_change_skips_tiles_it_cannot_affect() {
        let (cafe, bar) = cafe_bar();
        let poi_on = LayerToggles {
            poi: true,
            transit: false,
            traffic: false,
            safety: false,
        };
        // No POI features at all: any chip change is fresh.
        let bare = stamp(true, false, false, KindFilter::all(), vec![], false, false);
        assert!(!bare.stale_under(&poi_on, &kinds(&["cafe"])));
        // POIs, but none admitted before or after: still fresh.
        let other = stamp(
            true,
            false,
            false,
            kinds(&["cafe"]),
            vec![bar],
            false,
            false,
        );
        assert!(!other.stale_under(&poi_on, &kinds(&["restaurant"])));
        // An admitted kind leaves the set: stale.
        let food = stamp(
            true,
            false,
            false,
            KindFilter::all(),
            vec![cafe, bar],
            false,
            false,
        );
        assert!(food.stale_under(&poi_on, &kinds(&["cafe"])));
        // Widening over an already-fully-admitted tile: fresh.
        let all_food = stamp(
            true,
            false,
            false,
            kinds(&["cafe", "bar"]),
            vec![cafe],
            false,
            false,
        );
        assert!(!all_food.stale_under(&poi_on, &KindFilter::all()));
    }

    /// With POI off the filter is meaningless: meshes cannot differ, whatever the chips say.
    #[test]
    fn a_kind_change_with_poi_off_is_always_fresh() {
        let (cafe, _) = cafe_bar();
        let off = LayerToggles {
            poi: false,
            transit: false,
            traffic: false,
            safety: false,
        };
        let stamp = stamp(false, false, false, KindFilter::all(), vec![cafe], false, false);
        assert!(!stamp.stale_under(&off, &kinds(&["cafe"])));
    }

    /// Turning POI off rebuilds only tiles that actually show POIs; turning it on
    /// rebuilds only tiles whose source holds POI features.
    #[test]
    fn a_poi_flip_rebuilds_only_tiles_with_poi_content() {
        let (cafe, _) = cafe_bar();
        let on = LayerToggles {
            poi: true,
            transit: false,
            traffic: false,
            safety: false,
        };
        let off = LayerToggles {
            poi: false,
            transit: false,
            traffic: false,
            safety: false,
        };
        // Built with POI on, showing a cafe: turning off removes it.
        let showing = stamp(true, false, false, KindFilter::all(), vec![cafe], false, false);
        assert!(showing.stale_under(&off, &KindFilter::all()));
        // Built with POI on, but the filter admits nothing: turning off removes nothing.
        let showing_nothing = stamp(
            true,
            false,
            false,
            kinds(&["bar"]),
            vec![cafe],
            false,
            false,
        );
        assert!(!showing_nothing.stale_under(&off, &kinds(&["bar"])));
        // Built with POI off, source holds a cafe: turning on adds it.
        let holding = stamp(false, false, false, KindFilter::all(), vec![cafe], false, false);
        assert!(holding.stale_under(&on, &KindFilter::all()));
        // Built with POI off, source holds none: turning on adds nothing.
        let bare = stamp(false, false, false, KindFilter::all(), vec![], false, false);
        assert!(!bare.stale_under(&on, &KindFilter::all()));
    }

    /// Transit/traffic flips rebuild only tiles whose source carries the layer.
    #[test]
    fn a_transit_or_traffic_flip_skips_tiles_without_the_layer() {
        let base = LayerToggles {
            poi: false,
            transit: false,
            traffic: false,
            safety: false,
        };
        let transit_on = LayerToggles {
            transit: true,
            ..base
        };
        let traffic_on = LayerToggles {
            traffic: true,
            ..base
        };
        let bare = stamp(false, false, false, KindFilter::all(), vec![], false, false);
        assert!(!bare.stale_under(&transit_on, &KindFilter::all()));
        assert!(!bare.stale_under(&traffic_on, &KindFilter::all()));
        let transit_tile = stamp(false, false, false, KindFilter::all(), vec![], true, false);
        assert!(transit_tile.stale_under(&transit_on, &KindFilter::all()));
        assert!(!transit_tile.stale_under(&traffic_on, &KindFilter::all()));
        let traffic_tile = stamp(false, false, false, KindFilter::all(), vec![], false, true);
        assert!(traffic_tile.stale_under(&traffic_on, &KindFilter::all()));
        assert!(!traffic_tile.stale_under(&transit_on, &KindFilter::all()));
    }

    /// A safety flip rebuilds only tiles whose source carries safety features.
    #[test]
    fn a_safety_flip_skips_tiles_without_the_layer() {
        let off = LayerToggles::default();
        let on = LayerToggles { safety: true, ..off };
        let bare = stamp(false, false, false, KindFilter::all(), vec![], false, false);
        assert!(!bare.stale_under(&on, &KindFilter::all()));
        let mut with_safety = bare.clone();
        with_safety.gated.has_safety = true;
        assert!(with_safety.stale_under(&on, &KindFilter::all()));
        let built_on = ToggleStamp { toggles: on, ..with_safety };
        assert!(built_on.stale_under(&off, &KindFilter::all()));
    }

    /// Identical snapshots are never stale, and `admitted` honours the empty-means-all rule.
    #[test]
    fn an_unchanged_snapshot_is_fresh_and_admitted_matches_admits() {
        let (cafe, bar) = cafe_bar();
        let toggles = LayerToggles {
            poi: true,
            transit: true,
            traffic: true,
            safety: false,
        };
        let filter = kinds(&["cafe"]);
        let stamp = stamp(true, true, true, filter.clone(), vec![cafe, bar], true, true);
        assert!(!stamp.stale_under(&toggles, &filter));
        assert_eq!(filter.admitted(&[cafe, bar]), vec![cafe]);
        assert_eq!(
            KindFilter::all().admitted(&[bar, cafe]),
            vec![cafe.min(bar), cafe.max(bar)]
        );
    }
}
