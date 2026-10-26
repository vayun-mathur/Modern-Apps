impl Gemma4Handle {
    /// Feed one token and return its logits, or `None` while only filling the cache.
    fn step(&mut self, token: u32, want_logits: bool) -> Result<Option<Vec<f32>>, String> {
        if let Some(state) = self.fold_pending(token)? {
            return if want_logits { self.head_logits(&state).map(Some) } else { Ok(None) };
        }
        let (hidden, per_layer) = self.gather(token)?;
        self.step_hidden(&hidden, &per_layer, want_logits)
    }

    /// One token's embedding and per-layer inputs. The 36 int4 rows gather on the host
    /// (`int4_row` is mmap-cheap); the combine runs on `gather_net` when it built, else on host.
    fn gather(&mut self, token: u32) -> Result<(Vec<f32>, Vec<f32>), String> {
        // Host rows (both paths need them — see `gemma4_gather` module docs
        // for why the row lookups did not move).
        let reader = self.embed.reader();
        if token >= gemma4::VOCAB {
            return Err(format!("token {token} is past the {}-entry vocabulary", gemma4::VOCAB));
        }
        let hidden = reader.int4_row(
            gemma4::embed::TOKENS,
            gemma4::embed::TOKENS + 1,
            &[gemma4::VOCAB, gemma4::D_MODEL],
            token,
        )?;
        let per_layer_row = if gemma4::embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
        let mut embedded = Vec::with_capacity((gemma4::PER_LAYER as usize) * gemma4::LAYERS);
        for i in 0..gemma4::LAYERS {
            let at = gemma4::embed::table(i);
            embedded.extend(reader.int4_row(at, at + 1, &[gemma4::VOCAB, gemma4::PER_LAYER], per_layer_row)?);
        }
        drop(reader);
        let per_layer = self.combine(&hidden, &embedded)?;
        Ok((hidden, per_layer))
    }

    /// The per-layer block for already-gathered rows: on `gather_net` when it built, else the
    /// host `combine_cached` (same projection + grouped norm + scaled add).
    fn combine(&mut self, hidden: &[f32], embedded: &[f32]) -> Result<Vec<f32>, String> {
        self.combine_block(hidden, embedded, 1)
    }

    /// The per-layer block for `width` gathered columns laid out `[C, 1, width]`: one
    /// `gather_net` submit for all of them, else the host `combine_cached` column by column.
    fn combine_block(&mut self, hidden: &[f32], embedded: &[f32], width: u32) -> Result<Vec<f32>, String> {
        let len = (gemma4::PER_LAYER as usize) * gemma4::LAYERS * width as usize;
        if let Some(net) = self.gather_net.as_mut() {
            let out = net.at(width)?.infer_raw_many(&[hidden, embedded])?;
            if out.len() == 1 && out[0].len() == len {
                return Ok(out.into_iter().next().unwrap_or_default());
            }
            // Wrong shape: fall through to the host combine rather than failing.
        }
        if width == 1 {
            return gather_combine_host(&self.embed, hidden, embedded);
        }
        let (wide, mut out) = (width as usize, vec![0f32; len]);
        for at in 0..wide {
            let h = crate::nets::gemma4_gather::column(hidden, at, wide);
            let e = crate::nets::gemma4_gather::column(embedded, at, wide);
            let combined = gather_combine_host(&self.embed, &h, &e)?;
            crate::nets::gemma4_gather::place(&mut out, &combined, at, wide);
        }
        Ok(out)
    }

    /// Feed one token and return the greedy next token, reduced on the device.
    ///
    /// Same transformer step as [`step`](Self::step) but the head runs
    /// through the greedy plan (`build_plan_greedy`): 16 splits concatenate
    /// on-device, one `Argmax` reduces to the winning index, and 1 value
    /// crosses back instead of 262144 logits. Falls back to the host argmax
    /// when the head net is absent. The sampling path (`logitsGemma4`) never
    /// calls this — it needs the full logits.
    fn step_greedy(&mut self, token: u32) -> Result<u32, String> {
        if let Some(state) = self.fold_pending(token)? {
            return self.head_greedy(&state);
        }
        let (hidden, per_layer) = self.gather(token)?;
        self.step_hidden_greedy(&hidden, &per_layer)
    }

    /// The step itself, once both halves of the input are in hand.
    fn step_hidden(
        &mut self,
        hidden: &[f32],
        per_layer: &[f32],
        want_logits: bool,
    ) -> Result<Option<Vec<f32>>, String> {
        if self.position >= self.context {
            return Err(format!("the cache is full at {} positions", self.context));
        }
        let row = |table: &[f32], width: u32| -> Vec<f32> {
            let from = (self.position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let local = row(&self.local, gemma4::HEAD_DIM);
        let global = row(&self.global, gemma4::GLOBAL_HEAD_DIM);
        // Prefill positions do not need logits, and until now they computed them anyway: the
        // plan was `DecodeStep` unconditionally and `want_logits` only decided whether the host
        // kept the result. The head is four int4 projections over the 262,144-entry vocabulary,
        // 227 MB of the 1.30 GB of weights - 17.5 % of the bytes a pass touches, discarded on
        // every one of the ~1,870 positions a prompt is long.
        //
        // `Prefill { tokens: 1 }` runs all thirty-five layers, fills all thirty caches and stops
        // before the final norm and the head. Its one output is the hidden state, which nothing
        // here wants.
        //
        // SAFETY OF SWITCHING PLANS MID-CONVERSATION, which is not obvious and is not guaranteed
        // by the type system: `Reshaped::at` re-records whenever the mode changes, and the caches
        // live in the arena, so the two plans must agree on where they are. They do - measured,
        // not assumed: every cache operand is identical across the two, at all twenty-nine
        // offsets, because at `tokens: 1` the inputs are the same size and `finish` assigns arena
        // offsets by walking the pinned list in declaration order. That equality is pinned by a
        // test in `nets::gemma4`; if it ever fails, prefill writes the caches where decode does
        // not read them and the model answers fluently from whatever was in the arena.
        //
        // The arena never reallocates either, which would drop the caches outright: decode's is
        // the larger of the two, the net is created at decode, and `rebuild` only grows.
        let mode = if want_logits {
            gemma4::Mode::DecodeStep
        } else {
            gemma4::Mode::Prefill { tokens: 1 }
        }
        .at(self.context);
        let at = self.net.at(mode)?;
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let ran = std::time::Instant::now();
        let out = at.infer_raw_many(&[hidden, per_layer, &local, &global])?;
        // Every sixteenth, so the log is a sample rather than a flood.
        if self.position % 16 == 0 {
            log(&format!(
                "gemma4 decode at {}: {:.0} ms gpu, head {}",
                self.position,
                ran.elapsed().as_secs_f64() * 1000.0,
                if want_logits { "on" } else { "off" },
            ));
        }
        self.position += 1;
        if !want_logits {
            return Ok(None);
        }
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not 1 hidden state", out.len()));
        }
        let hidden = &out[0];
        if hidden.len() != gemma4::D_MODEL as usize {
            return Err(format!("hidden state of {} values, not {}", hidden.len(), gemma4::D_MODEL));
        }
        Ok(Some(self.head_logits(hidden)?))
    }

    /// The greedy step itself, once both halves of the input are in hand.
    ///
    /// Same transformer submit as [`step_hidden`](Self::step_hidden) with
    /// `want_logits`, then the device argmax via
    /// [`head_greedy`](Self::head_greedy): 1 value back, no 512 KB readback,
    /// no host scan. `stepGemma4` calls this; `logitsGemma4` keeps calling
    /// `step_hidden` for the full logits.
    fn step_hidden_greedy(
        &mut self,
        hidden: &[f32],
        per_layer: &[f32],
    ) -> Result<u32, String> {
        let state = self.decode_state(hidden, per_layer)?;
        self.head_greedy(&state)
    }

    /// The transformer half of a decode step (DecodeStep, same params and cache discipline as
    /// the logits path): the normed hidden state the head reads.
    fn decode_state(&mut self, hidden: &[f32], per_layer: &[f32]) -> Result<Vec<f32>, String> {
        if self.position >= self.context {
            return Err(format!("the cache is full at {} positions", self.context));
        }
        let row = |table: &[f32], width: u32| -> Vec<f32> {
            let from = (self.position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let local = row(&self.local, gemma4::HEAD_DIM);
        let global = row(&self.global, gemma4::GLOBAL_HEAD_DIM);
        let mode = gemma4::Mode::DecodeStep.at(self.context);
        let at = self.net.at(mode)?;
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[hidden, per_layer, &local, &global])?;
        self.position += 1;
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not 1 hidden state", out.len()));
        }
        let state = out.into_iter().next().unwrap_or_default();
        if state.len() != gemma4::D_MODEL as usize {
            return Err(format!("hidden state of {} values, not {}", state.len(), gemma4::D_MODEL));
        }
        Ok(state)
    }

    /// Logits for `hidden`, GPU head when the EMBED file carries chunks.
    ///
    /// The device runs the 16 chunk projections and the host concatenates
    /// the splits, then the same softcap path as the host head below. Falls
    /// back to the host head on files without chunks (or when the head net
    /// failed to build).
    fn head_logits(&mut self, hidden: &[f32]) -> Result<Vec<f32>, String> {
        let Some(head) = self.head.as_mut() else {
            return self.tied_head_logits(hidden);
        };
        let at = head.at(crate::nets::gemma4_head::HeadOut::Logits)?;
        let out = at.infer_raw_many(&[hidden])?;
        if out.len() != crate::nets::gemma4_head::HEAD_CHUNKS {
            return Err(format!(
                "the head returned {} splits, not {}",
                out.len(),
                crate::nets::gemma4_head::HEAD_CHUNKS
            ));
        }
        let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
        for split in &out {
            if split.len() != crate::nets::gemma4_head::CLASSES_PER_CHUNK as usize {
                return Err(format!("a head split of {} values, not {}", split.len(), crate::nets::gemma4_head::CLASSES_PER_CHUNK));
            }
            for &value in split {
                logits.push(gemma4::LOGIT_CAP * (value / gemma4::LOGIT_CAP).tanh());
            }
        }
        Ok(logits)
    }

    /// The greedy next token for `hidden`, reduced on the device.
    ///
    /// `head.at(Greedy)`: the splits concatenate and reduce on the device, so two values
    /// ([lo, hi] of the id) come back instead of 262144 logits. Host argmax without a head file.
    fn head_greedy(&mut self, hidden: &[f32]) -> Result<u32, String> {
        let Some(head) = self.head.as_mut() else {
            let logits = self.head_logits(hidden)?;
            return Ok(argmax(&logits));
        };
        let at = head.at(crate::nets::gemma4_head::HeadOut::Greedy)?;
        let out = at.infer_raw_many(&[hidden])?;
        if out.len() != 1 {
            return Err(format!("greedy head returned {} outputs, not 1", out.len()));
        }
        let id_out = &out[0];
        if id_out.len() != 2 {
            return Err(format!("greedy id holds {} values, not 2", id_out.len()));
        }
        Ok(id_out[1] as u32 * 2048 + id_out[0] as u32)
    }

    /// Tied-head logits on the host: `hidden @ H^T` over the raw-scale head table.
    ///
    /// The head table is int4 `[VOCAB, D_MODEL]` (S10's `embedder.decode`
    /// composite), dequantised a quarter at a time (see `HEAD_SPLITS`) to
    /// bound the transient fp32. `LOGIT_CAP` softcapping matches the
    /// reference's `_post_process_decoding`. A Vulkan tied-head GEMM
    /// (chunked over vocab, table resident in VRAM) is the follow-up that
    /// removes this ~0.4 GFLOP host matmul from every step.
    ///
    /// The table is raw-scale (see [`gemma4::EMBED_GAIN`]): no division, no
    /// un-scaling - the dots go straight to the softcap, exactly as the
    /// reference's `decode_softmax` does.
    fn tied_head_logits(&self, hidden: &[f32]) -> Result<Vec<f32>, String> {
        use gemma4::embed;
        let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
        for split in 0..gemma4::HEAD_SPLITS {
            let start = (split as u32) * gemma4::CLASSES_PER_SPLIT;
            let block = self.embed.reader().fp16_rows(
                embed::HEAD_TABLE,
                &[gemma4::VOCAB, gemma4::D_MODEL],
                start,
                gemma4::CLASSES_PER_SPLIT,
            )?;
            for row in block.chunks_exact(gemma4::D_MODEL as usize) {
                let dot: f32 = row.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum();
                let capped = gemma4::LOGIT_CAP * (dot / gemma4::LOGIT_CAP).tanh();
                logits.push(capped);
            }
        }
        Ok(logits)
    }


    /// Reallocate the caches so at least `needed` positions fit. Returns the new capacity.
    ///
    /// # This throws the cache away
    ///
    /// The caches live in the arena, and a bigger arena is a different allocation - nothing
    /// copies the old contents across. So a grow resets the position to zero and the caller has
    /// to feed the whole prompt again.
    ///
    /// That is why the tiers double rather than creep: over a long conversation the re-prefill
    /// happens four times, not once per message. The caller is told by the return value, which
    /// it must treat as "the cache is now empty".
    fn grow(&mut self, needed: u32) -> Result<u32, String> {
        if needed <= self.context {
            return Ok(self.context);
        }
        let Some(tier) = gemma4::CONTEXT_TIERS
            .iter()
            .copied()
            .find(|tier| *tier >= needed && *tier <= self.ceiling)
        else {
            return Err(format!(
                "{needed} positions, and this device's cache stops at {}",
                self.ceiling
            ));
        };
        // Carry the cache across rather than dropping it.
        //
        // A bigger arena is a different allocation, so nothing moves by itself - and the first
        // version of this simply reset to zero and let the caller re-feed. That is very
        // expensive and, worse, silently undoes the precomputed prefix: `loadPrefix` grows to
        // fit the ~1,870-position system block and tool declarations, then the first turn needs
        // room for a reply, grows again, and the cache that was just loaded from disk is gone.
        //
        // Copying it out and back costs one staging round trip - tens of milliseconds against
        // the tens of seconds of prefill it saves.
        let carried = if self.position > 0 {
            let at = self.net.at(gemma4::Mode::DecodeStep.at(self.context))?;
            Some(at.export_pinned(gemma4::CACHE_TENSORS, self.position)?)
        } else {
            None
        };
        let mut rebuilt = Reshaped::streamed(
            context::shared()?,
            self.weights.offsets(),
            &self.weights,
            gemma4::Mode::DecodeStep.at(tier),
            gemma4_plan,
        )?;
        warm_gemma4(&mut rebuilt, tier)?;
        if let Some(bytes) = &carried {
            let at = rebuilt.at(gemma4::Mode::DecodeStep.at(tier))?;
            at.import_pinned(gemma4::CACHE_TENSORS, self.position, bytes)?;
        }
        log(&format!(
            "gemma4 cache grew {} -> {tier} positions ({} MB), so the prompt is re-fed",
            self.context,
            (u64::from(tier) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        ));
        log(&format!(
            "gemma4 carried {} positions into the new cache",
            if carried.is_some() { self.position } else { 0 }
        ));
        self.net = rebuilt;
        self.context = tier;
        // `self.position` is deliberately kept: the cache came with it.
        Ok(tier)
    }

} // close impl Gemma4Handle: everything below is a free function.
