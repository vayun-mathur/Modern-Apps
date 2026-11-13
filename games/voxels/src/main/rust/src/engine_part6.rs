// Engine-state tests that need a whole (small) world. They build their own `EngineState` with
// `new_state` rather than going through the global engine, so they can run in parallel.
#[cfg(test)]
mod state_tests {
    use super::*;

    /// A throwaway world directory under the system temp dir.
    pub(super) fn temp_world(tag: &str) -> String {
        let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let dir = std::env::temp_dir().join(format!("voxels_{tag}_{}_{n}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        dir.to_string_lossy().into_owned()
    }

    /// Runs `f` on a thread with a large stack: chunks are ~130 KB and debug builds keep several
    /// copies of one on the stack while generating, which overflows the 2 MB test-thread default.
    pub(super) fn big_stack(f: impl FnOnce() + Send + 'static) {
        let h = std::thread::Builder::new().stack_size(64 << 20).spawn(f);
        if let Ok(h) = h { if let Err(e) = h.join() { std::panic::resume_unwind(e); } }
    }

    /// A tiny world (radius 2) so world generation stays fast in debug builds.
    pub(super) fn small_state(tag: &str) -> EngineState { new_state(temp_world(tag), 1234, 2) }

    // Autosave writes player, chests and edited chunks together, and a reload sees all of them.
    #[test]
    fn autosave_persists_player_and_chunks_together() { big_stack(|| {
        let mut s = small_state("autosave");
        let dir = s.save_dir.clone();
        s.inventory.slots[0] = crate::inventory::InvSlot { id: 8, count: 5 };
        s.player.pos = vec3(3.5, 90.0, 4.5);
        let _ = s.chunks.set_block_world(1, 100, 1, 9);
        // Not yet: the timer is still running.
        tick_autosave(&mut s, AUTOSAVE_SECS * 0.5);
        assert!(crate::world::save::load_player(&dir).is_none(), "saved before the interval elapsed");
        tick_autosave(&mut s, AUTOSAVE_SECS * 0.6);
        let saved = crate::world::save::load_player(&dir).expect("autosave wrote player.json");
        assert_eq!(saved.inventory.slots[0].id, 8);
        assert_eq!(saved.inventory.slots[0].count, 5);
        assert!((saved.x - 3.5).abs() < 1e-4);
        assert!((s.autosave_cd - AUTOSAVE_SECS).abs() < 1e-4, "the timer restarts after a save");
        let reloaded = new_state(dir, 1234, 2);
        assert_eq!(reloaded.chunks.get_block_world(1, 100, 1), 9, "the edited chunk was saved with the player");
    }) }

    // An edited chunk that leaves range is held until the next consistent save rather than written
    // on its own, and coming back to it before then returns the edit from memory.
    #[test]
    fn unloaded_edits_wait_for_the_next_save() { big_stack(|| {
        let dir = temp_world("pending");
        let mut map = ChunkMap::new(7, dir.clone());
        map.render_distance = 1;
        map.ensure_radius(0, 0);
        assert!(map.set_block_world(2, 100, 2, 9));
        // Walk far enough away that chunk (0,0) unloads.
        map.ensure_radius(16 * 10, 0);
        assert_eq!(map.pending_len(), 1, "the edited chunk is pending, not dropped");
        assert!(!std::path::Path::new(&dir).join("regions").join("c_0_0.dat").exists(), "written outside a save");
        // Coming back before a save returns the in-memory edit.
        map.ensure_radius(0, 0);
        assert_eq!(map.get_block_world(2, 100, 2), 9);
        assert_eq!(map.pending_len(), 0);
        // Leave again, then save: the file appears and nothing stays pending.
        map.ensure_radius(16 * 10, 0);
        map.save_dirty();
        assert_eq!(map.pending_len(), 0);
        assert!(std::path::Path::new(&dir).join("regions").join("c_0_0.dat").exists());
        let mut fresh = ChunkMap::new(7, dir);
        fresh.render_distance = 0;
        fresh.ensure_radius(0, 0);
        assert_eq!(fresh.get_block_world(2, 100, 2), 9);
    }) }

    // The pause menu freezes a single-player world: nothing moves and the autosave clock stops,
    // while unpausing resumes exactly where it left off.
    #[test]
    fn a_paused_world_does_not_simulate() { big_stack(|| {
        let mut s = small_state("paused");
        s.player.flying = false;
        s.player.pos = vec3(0.5, 120.0, 0.5);
        s.player.vel = Vec3::ZERO;
        let (pos, cd) = (s.player.pos, s.autosave_cd);
        for _ in 0..10 { step(&mut s, 0.05, true); }
        assert_eq!(s.player.pos, pos, "the player moved while paused");
        assert!((s.autosave_cd - cd).abs() < 1e-6, "the autosave clock ran while paused");
        for _ in 0..10 { step(&mut s, 0.05, false); }
        assert!(s.player.pos.y < pos.y, "gravity resumes after unpausing");
        assert!(s.autosave_cd < cd);
    }) }
}
