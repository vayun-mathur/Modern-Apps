//! The `safety` layer: speed cameras, ALPR, surveillance cameras, stop signs and traffic signals.
//!
//! Classification is [`osm_ingest::safety::classify`], the port of `normalize_safety.py`; this
//! module only adapts tags to it and interns the result. Nodes only — extract calls it from the
//! node pass, never for a way or a relation.

use osm_ingest::safety::{self, SafetyTags};
use tilecodec::mamaps::dict;

use super::{kind, Class, TagSource};

/// Carried at the archive max only: dense at every intersection and drawn from z16 by overzoom.
pub const MIN_ZOOM: u8 = 14;

#[cfg(test)]
pub const KINDS: &[&str] = &["speed_camera", "alpr", "surveillance", "stop_sign", "traffic_signals"];

pub const FILTERS: &[&str] = &["highway", "man_made", "enforcement"];

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    let t = SafetyTags {
        highway: tags.get("highway"),
        man_made: tags.get("man_made"),
        enforcement: tags.get("enforcement"),
        surveillance_type: tags.get("surveillance:type"),
        camera_type: tags.get("camera:type"),
        operator: tags.get("operator"),
        manufacturer: tags.get("manufacturer"),
        ..SafetyTags::default()
    };
    let found = safety::classify(&t)?;
    Some(Class::line(dict::LAYER_SAFETY, kind(found.as_str()), MIN_ZOOM))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kind_of(tags: &[(&str, &str)]) -> Option<&'static str> {
        classify(tags).map(|c| {
            assert_eq!(c.layer, dict::LAYER_SAFETY);
            dict::KINDS[c.kind as usize - 1]
        })
    }

    #[test]
    fn each_kind_classifies() {
        assert_eq!(kind_of(&[("highway", "stop")]), Some("stop_sign"));
        assert_eq!(kind_of(&[("highway", "traffic_signals")]), Some("traffic_signals"));
        assert_eq!(kind_of(&[("highway", "speed_camera")]), Some("speed_camera"));
        assert_eq!(
            kind_of(&[("man_made", "surveillance"), ("enforcement", "maxspeed")]),
            Some("speed_camera"),
        );
        assert_eq!(
            kind_of(&[("man_made", "surveillance"), ("surveillance:type", "ALPR")]),
            Some("alpr"),
        );
        assert_eq!(kind_of(&[("man_made", "surveillance")]), Some("surveillance"));
        assert_eq!(kind_of(&[("highway", "residential")]), None);
        assert_eq!(kind_of(&[("amenity", "cafe")]), None);
    }
}
