//! JSON-facing blessing behaviour: the attunement summary the HUD reads
//! and the catalog the blessing screen lists.

use super::*;

#[test]
fn attunement_json_describes_each_slot() {
    let empty: serde_json::Value = serde_json::from_str(&Attunement::default().to_json()).unwrap();
    let slots = empty["slots"].as_array().unwrap();
    assert_eq!(slots.len(), SLOTS);
    assert!(slots.iter().all(|s| s["id"] == 0 && s["name"] == "" && s["effect"] == ""));

    let mut a = Attunement::default();
    assert!(a.attune(1056));
    assert!(a.attune(1145));
    let v: serde_json::Value = serde_json::from_str(&a.to_json()).unwrap();
    assert_eq!(v["slots"][0]["name"], "Blessing of Clement");
    assert_eq!(v["slots"][1]["name"], "Blessing of Anubis");
    assert_eq!(v["slots"][1]["effect"], find(1145).unwrap().effect);
    assert_eq!(v["slots"][2]["id"], 0, "the unbound slot stays blank");
}

#[test]
fn catalog_json_lists_the_whole_pantheon() {
    let v: serde_json::Value = serde_json::from_str(&catalog_json()).unwrap();
    let list = v.as_array().unwrap();
    assert_eq!(list.len(), PANTHEON.len());
    for (entry, blessing) in list.iter().zip(PANTHEON.iter()) {
        assert_eq!(entry["id"], blessing.id);
        assert_eq!(entry["name"], blessing.name);
        assert_eq!(entry["effect"], blessing.effect);
    }
}

#[test]
fn attunement_edges_are_total() {
    let mut a = Attunement::default();
    assert_eq!(a.release(0), None, "releasing an empty slot gives nothing");
    assert_eq!(a.release(SLOTS), None, "releasing past the end gives nothing, not a panic");
    assert_eq!(a.release(usize::MAX), None);
    // A corrupt slot id (not a blessing) is inert: it grants no passive and
    // cannot block attuning, but releasing it still returns the raw id.
    let mut corrupt = Attunement { slots: [9999, 0, 0] };
    assert!(!corrupt.has(Passive::Traversal));
    assert!(corrupt.contains(9999) && !corrupt.contains(1056));
    assert!(find(9999).is_none());
    assert!(find(0).is_none());
    assert!(!is_blessing(0) && is_blessing(PANTHEON[0].id));
    assert_eq!(corrupt.release(0), Some(9999));
    assert!(corrupt.attune(1056), "the freed slot can be bound again");
}

#[test]
fn blessing_constructor_at_runtime() {
    let entry = b(1056, Passive::Traversal, "icon.png", "Name", "Effect");
    assert_eq!(entry.id, 1056);
    assert_eq!(entry.passive, Passive::Traversal);
    assert_eq!(entry.icon, "icon.png");
    assert_eq!(entry.name, "Name");
    assert_eq!(entry.effect, "Effect");
    // Every pantheon entry is findable by its own id.
    for entry in PANTHEON.iter() {
        assert_eq!(find(entry.id).map(|f| f.id), Some(entry.id));
        assert!(is_blessing(entry.id));
    }
    assert!(!is_blessing(0));
    assert!(find(0).is_none());
}
