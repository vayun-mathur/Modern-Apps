//! Tests for the pure CSV parser behind the JNI start-race glue.
//! The `extern "system"` entry points themselves need a JVM `Env` and are
//! pure JNI glue — they forward to the engine functions tested elsewhere.
use super::*;

#[test]
fn parse_player_ids_trims_and_drops_empties() {
    assert_eq!(parse_player_ids("p1,p2"), vec!["p1", "p2"]);
    assert_eq!(parse_player_ids("  p1 ,  p2  "), vec!["p1", "p2"]);
    assert_eq!(parse_player_ids(""), Vec::<String>::new());
    assert_eq!(parse_player_ids(",,,"), Vec::<String>::new());
    assert_eq!(parse_player_ids("p1,,p2,"), vec!["p1", "p2"]);
    assert_eq!(parse_player_ids("solo"), vec!["solo"]);
    assert_eq!(parse_player_ids(" , "), Vec::<String>::new());
    // Inner spaces in an id are preserved; only the ends are trimmed.
    assert_eq!(parse_player_ids("player one,p2"), vec!["player one", "p2"]);
}
