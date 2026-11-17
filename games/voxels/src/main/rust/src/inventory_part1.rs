impl Inventory {
    pub fn selected_block(&self) -> Id {
        if self.selected < HOTBAR { let s = &self.slots[self.selected]; if s.count > 0 { s.id } else { 0 } } else { 0 }
    }
    pub fn consume_selected(&mut self) -> Option<Id> {
        if self.selected >= HOTBAR { return None; }
        let slot = &mut self.slots[self.selected];
        if slot.count <= 0 || slot.id == 0 { return None; }
        let id = slot.id;
        slot.count -= 1;
        if slot.count <= 0 { *slot = InvSlot::default(); }
        self.placed += 1;
        Some(id)
    }
    pub fn add_block(&mut self, id: Id) { self.try_add_block(id); }
    // Add one unit, reporting whether it actually fit anywhere.
    pub fn try_add_block(&mut self, id: Id) -> bool {
        if id == 0 { return false; }
        for slot in self.slots.iter_mut() { if slot.id == id && slot.count < STACK { slot.count += 1; return true; } }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = 1; return true; } }
        false
    }
    pub fn select(&mut self, idx: usize) { if idx < HOTBAR { self.selected = idx; } }

    // Drag-and-drop: move/merge/swap the stack in `from` into `to`.
    pub fn move_item(&mut self, from: usize, to: usize) {
        if from >= SLOTS || to >= SLOTS || from == to { return; }
        let a = self.slots[from];
        let b = self.slots[to];
        if a.id == 0 { return; }
        if b.id == 0 {
            self.slots[to] = a; self.slots[from] = InvSlot::default();
        } else if a.id == b.id && !crate::item::has_durability(a.id) {
            // Only stackables merge. A durable item's count is its remaining
            // durability, so merging two of them would fuse two tools into
            // one slot with summed (possibly over-maximum) durability and
            // silently delete the other; those swap below instead.
            let space = STACK - b.count;
            let mv = a.count.min(space);
            self.slots[to].count += mv;
            self.slots[from].count -= mv;
            if self.slots[from].count <= 0 { self.slots[from] = InvSlot::default(); }
        } else {
            self.slots[from] = b; self.slots[to] = a;
        }
    }

    // Creative catalog: put a full stack of `id` into the first empty (or matching) slot.
    pub fn give(&mut self, id: Id) {
        if id == 0 { return; }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = STACK; return; } }
        for slot in self.slots.iter_mut() { if slot.id == id { slot.count = STACK; return; } }
    }

    pub fn count_of(&self, id: Id) -> i32 { self.slots.iter().filter(|s| s.id == id).map(|s| s.count).sum() }
    fn remove_count(&mut self, id: Id, mut n: i32) {
        for slot in self.slots.iter_mut() {
            if slot.id == id && n > 0 {
                let take = slot.count.min(n);
                slot.count -= take; n -= take;
                if slot.count <= 0 { *slot = InvSlot::default(); }
            }
        }
    }
    /// Craft one batch. `crafted` is the set of recipes already made, which is what gates the tech
    /// tree: a locked recipe consumes nothing and reports failure.
    pub fn craft(&mut self, recipe: usize, crafted: &[bool]) -> bool {
        if !recipe_unlocked(recipe, crafted) { return false; }
        let Some(&Recipe { in1, n1, in2, n2, out, out_n, .. }) = RECIPES.get(recipe) else { return false; };
        if self.count_of(in1) < n1 { return false; }
        if in2 != 0 && self.count_of(in2) < n2 { return false; }
        // `has_room_for` already knows a tool needs a whole empty slot, so this covers both cases.
        // Skipping it for durable output would craft a tool into a full inventory and drop it.
        if !self.has_room_for(out, out_n) { return false; }
        self.remove_count(in1, n1);
        if in2 != 0 { self.remove_count(in2, n2); }
        if crate::item::has_durability(out) { self.add_item_with_count(out, crate::item::max_durability(out)); }
        else { for _ in 0..out_n { self.add_block(out); } }
        true
    }
    // Place a non-stacking item (tool/armor) into the first empty slot with a given count (durability).
    pub fn add_item_with_count(&mut self, id: Id, count: i32) -> bool {
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = count; return true; } }
        false
    }
    pub fn selected_count(&self) -> i32 { if self.selected < HOTBAR { self.slots[self.selected].count } else { 0 } }
    // Remove the whole selected stack, returning (id, count). Used to equip armor without losing durability.
    pub fn take_selected(&mut self) -> Option<(Id, i32)> {
        if self.selected >= HOTBAR { return None; }
        let s = self.slots[self.selected];
        if s.id == 0 { return None; }
        self.slots[self.selected] = InvSlot::default();
        Some((s.id, s.count))
    }
    // Decrement the selected tool's durability (called on block break / attack); clear it at 0.
    pub fn damage_selected(&mut self) {
        if self.selected >= HOTBAR { return; }
        let s = &mut self.slots[self.selected];
        if s.id != 0 && crate::item::has_durability(s.id) { s.count -= 1; if s.count <= 0 { *s = InvSlot::default(); } }
    }
    // Equip an armor item (with its durability) into its slot; returns any displaced piece.
    pub fn equip_armor(&mut self, id: Id, dur: i32) -> Option<InvSlot> {
        let slot = crate::item::armor_slot(id);
        let old = self.armor[slot];
        self.armor[slot] = InvSlot { id, count: dur };
        if old.id != 0 { Some(old) } else { None }
    }
    // Execute a villager trade if the player can afford it and has somewhere to put the goods.
    // Both costs are checked before either is taken, so a half-affordable trade consumes nothing.
    pub fn trade_offer(&mut self, o: &crate::villager::Offer) -> bool {
        let give_n = crate::villager::give_count(o);
        if self.count_of(o.cost) < o.cost_n { return false; }
        if o.cost2 != 0 && self.count_of(o.cost2) < o.cost2_n { return false; }
        if !self.has_room_for(o.give, give_n) { return false; }
        self.remove_count(o.cost, o.cost_n);
        if o.cost2 != 0 { self.remove_count(o.cost2, o.cost2_n); }
        if crate::item::has_durability(o.give) { self.add_item_with_count(o.give, give_n); }
        else { for _ in 0..give_n { self.add_block(o.give); } }
        true
    }
    // Execute a stonecutter conversion if the player has the input and room for the output.
    pub fn cut(&mut self, idx: usize) -> bool {
        let cuts = cut_variants();
        let Some(c) = cuts.get(idx) else { return false; };
        if self.count_of(c.input) < 1 { return false; }
        if !self.has_room_for(c.output, c.count) { return false; }
        self.remove_count(c.input, 1);
        for _ in 0..c.count { self.add_block(c.output); }
        true
    }
    pub fn armor_defense(&self) -> f32 { self.armor.iter().map(|s| if s.id != 0 { crate::item::armor_defense(s.id) } else { 0.0 }).sum() }

    // ---- Smelting ----
    pub fn can_smelt(&self, s: &Smelt) -> bool {
        self.count_of(s.in1) >= s.n1 && (s.in2 == 0 || self.count_of(s.in2) >= s.n2)
    }
    // Consume one batch of a smelt recipe's inputs and bank its output. Callers must check
    // `can_smelt` first; this is only reached once the furnace has finished a cycle.
    pub fn take_smelt_inputs(&mut self, s: &Smelt) {
        self.remove_count(s.in1, s.n1);
        if s.in2 != 0 { self.remove_count(s.in2, s.n2); }
    }
    pub fn give_smelt_output(&mut self, s: &Smelt) {
        for _ in 0..s.out_n { self.add_block(s.out); }
    }
    // Whether `n` of `id` would actually fit. Used to pause a furnace rather than smelt into a full
    // inventory and drop the result on the floor.
    pub fn has_room_for(&self, id: Id, n: i32) -> bool {
        if crate::item::has_durability(id) { return self.slots.iter().any(|s| s.id == 0); }
        let mut need = n;
        for s in self.slots.iter() {
            if s.id == 0 { need -= STACK; } else if s.id == id { need -= STACK - s.count; }
            if need <= 0 { return true; }
        }
        need <= 0
    }
    // Burn one fuel item, returning how many seconds of heat it provides. `spare` lists ids the
    // active recipe needs as ingredients so the furnace never eats its own input (sulfur is both a
    // fuel and the second half of the steel recipe). Dedicated fuels are preferred over logs and
    // planks so a smelt doesn't quietly consume the player's building stock.
    pub fn consume_fuel(&mut self, spare: &[Id]) -> Option<f32> {
        let usable = |s: &InvSlot| s.count > 0 && fuel_secs(s.id) > 0.0 && !spare.contains(&s.id);
        let idx = self.slots.iter().position(|s| usable(s) && DEDICATED_FUELS.contains(&s.id))
            .or_else(|| self.slots.iter().position(usable))?;
        let secs = fuel_secs(self.slots[idx].id);
        self.slots[idx].count -= 1;
        if self.slots[idx].count <= 0 { self.slots[idx] = InvSlot::default(); }
        Some(secs)
    }

    // ---- Containers ----
    // Pull a stack out of a container slot. Whatever doesn't fit stays in `slot`, so a full
    // inventory can never silently void a chest.
    pub fn take_from(&mut self, slot: &mut InvSlot) {
        if slot.id == 0 || slot.count <= 0 { return; }
        if crate::item::has_durability(slot.id) {
            if self.add_item_with_count(slot.id, slot.count) { *slot = InvSlot::default(); }
            return;
        }
        while slot.count > 0 {
            if !self.try_add_block(slot.id) { return; }
            slot.count -= 1;
        }
        *slot = InvSlot::default();
    }
    // Wear down each equipped piece by 1 when the player is hurt.
    pub fn damage_armor(&mut self) {
        for s in self.armor.iter_mut() { if s.id != 0 { s.count -= 1; if s.count <= 0 { *s = InvSlot::default(); } } }
    }
    // Lu Ban: restore a single point of durability to the most worn piece of gear carried or worn.
    pub fn mend_one(&mut self) {
        let worst = self.slots.iter_mut().chain(self.armor.iter_mut())
            .filter(|s| s.id != 0 && crate::item::has_durability(s.id) && s.count < crate::item::max_durability(s.id))
            .min_by_key(|s| s.count);
        if let Some(s) = worst { s.count += 1; }
    }

    pub fn to_json(&self) -> String { serde_json::to_string(self).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.to_string()) }
}

// Serialize the fixed-size slot array as a plain list (serde can't derive for [T; 36]).
mod serde_slots {
    use super::{InvSlot, SLOTS};
    use serde::{Deserializer, Serializer, Deserialize, Serialize};
    pub fn serialize<S: Serializer>(slots: &[InvSlot; SLOTS], s: S) -> Result<S::Ok, S::Error> {
        slots.as_slice().serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[InvSlot; SLOTS], D::Error> {
        let v: Vec<InvSlot> = Vec::deserialize(d)?;
        let mut out = [InvSlot::default(); SLOTS];
        for (i, slot) in v.into_iter().enumerate().take(SLOTS) { out[i] = slot; }
        Ok(out)
    }
}
