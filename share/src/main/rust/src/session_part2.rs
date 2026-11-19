impl Session {
    fn handle_payload_transfer(&mut self, pt: frame::PayloadTransferFrame) -> i32 {
        if pt.packet_type != PayloadPacketType::Data as i32 {
            return 0;
        }
        let Some(chunk) = pt.payload_chunk else {
            return 0;
        };
        let (payload_id, payload_type, name, total_size) = match pt.payload_header.as_ref() {
            Some(h) => (h.id, h.r#type, h.file_name.clone(), h.total_size),
            None => {
                // Continuation chunk with no header: it belongs to the payload the
                // previous DATA frame identified. `:share` repeats the header on
                // every chunk, so this only happens for a peer that does not.
                let Some(id) = self.last_data_payload_id else {
                    return 0;
                };
                let Some(entry) = self.recvs.get(&id) else {
                    return 0;
                };
                (id, PayloadType::File as i32, entry.name.clone(), entry.expected_size)
            }
        };
        self.last_data_payload_id = Some(payload_id);
        // The peer's chunking convention, measured rather than assumed: a BYTES payload may
        // arrive as one body+FLAG_LAST chunk or as a body chunk closed by an empty one.
        self.note(format!(
            "in PAYLOAD id {payload_id} type {payload_type} off {} len {} flags {}",
            chunk.offset(),
            chunk.body().len(),
            chunk.flags(),
        ));

        if payload_type == PayloadType::Bytes as i32 {
            // A BYTES payload carries a Sharing Frame, but not necessarily in one chunk:
            // the body may arrive on a chunk with no flags and be closed by a later,
            // empty chunk carrying FLAG_LAST. Reassemble by offset and dispatch on the
            // flag, which handles both that form and a single body+FLAG_LAST chunk.
            let last = chunk.is_last();
            let offset = usize::try_from(chunk.offset()).unwrap_or(usize::MAX);
            let have = self.bytes_recvs.entry(payload_id).or_default();
            let gap = offset > have.len();
            if offset == have.len() {
                // Unbounded reassembly lets a peer grow this buffer with non-final
                // chunks until allocation failure aborts the process. A sharing
                // frame is a control message; one frame's cap is generous for it.
                if have.len().saturating_add(chunk.body().len()) > frame::MAX_FRAME_LEN {
                    let have_len = have.len();
                    let _ = self.bytes_recvs.remove(&payload_id);
                    self.note(format!(
                        "BYTES {payload_id} over frame cap: have {have_len}, chunk {}",
                        chunk.body().len()
                    ));
                    return 0;
                }
                have.extend_from_slice(chunk.body());
            }
            if gap {
                let have_len = have.len();
                let _ = self.bytes_recvs.remove(&payload_id);
                self.note(format!("BYTES {payload_id} gap: chunk at {offset}, have {have_len}"));
                return 0;
            }
            if !last {
                return 0;
            }
            let body = self.bytes_recvs.remove(&payload_id).unwrap_or_default();
            return self.handle_sharing_frame(&body);
        }

        let entry = self.recvs.entry(payload_id).or_insert_with(|| ActiveRecv {
            name,
            expected_size: total_size,
            next_offset: 0,
            completed: false,
        });
        let last = chunk.is_last();
        let record = ReceivedChunk {
            payload_id,
            offset: chunk.offset(),
            total_size: entry.expected_size,
            last,
            name: entry.name.clone(),
            body: chunk.body.unwrap_or_default(),
        };
        entry.next_offset = entry.next_offset.saturating_add(record.body.len() as i64);
        if last {
            entry.completed = true;
        }
        // Queued, not accumulated: the body leaves the session on the next
        // `drain_received`, so a multi-gigabyte payload costs one chunk of memory.
        self.received_queue.push_back(record);
        if last {
            let all_done = !self.recvs.is_empty() && self.recvs.values().all(|r| r.completed);
            if all_done {
                self.state = State::Completed;
            }
        }
        0
    }

    fn handle_sharing_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(sharing) = payload::parse_sharing_frame(body) else {
            // Not fatal: a BYTES payload we do not understand is ignorable.
            self.note(format!("in Sharing frame undecodable ({}B)", body.len()));
            return 0;
        };
        let Some(v1) = sharing.v1 else {
            self.note("in Sharing frame without v1".to_string());
            return 0;
        };
        self.note(format!("in Sharing type {}", v1.r#type));
        if v1.r#type == SharingFrameType::PairedKeyEncryption as i32 {
            // Ours has normally gone out already, from `enter_paired_key`; this covers a peer
            // that somehow beat our own `CONNECTION_RESPONSE` handling.
            let rc = self.send_paired_key_encryption();
            if rc < 0 {
                return rc;
            }
            if !self.paired_key_result_sent {
                self.paired_key_result_sent = true;
                // UNABLE, not FAIL: we cannot verify a paired key at all, as
                // opposed to having verified one and rejected it (p000\duvz.java).
                let reply = payload::build_paired_key_result(PairedKeyResultStatus::Unable);
                let rc = self.send_sharing(&reply);
                if rc < 0 {
                    return rc;
                }
            }
            self.maybe_enter_ready();
            return 0;
        }
        if v1.r#type == SharingFrameType::PairedKeyResult as i32 {
            self.peer_paired_key_result_seen = true;
            self.maybe_enter_ready();
            return 0;
        }
        if v1.r#type == SharingFrameType::Cancel as i32 {
            self.fail("peer cancelled the transfer");
            return -2;
        }
        if v1.r#type == SharingFrameType::Introduction as i32 {
            let Some(intro) = v1.introduction else {
                return 0;
            };
            let files = payload::introduction_files(&intro);
            if files.is_empty() && intro.text_metadata.is_empty() {
                return 0;
            }
            self.pending_files = files;
            if self.state == State::Handshaking {
                self.state = State::AwaitingAccept;
            }
            return 0;
        }
        if v1.r#type == SharingFrameType::Response as i32 {
            let Some(resp) = v1.connection_response else {
                return 0;
            };
            return self.handle_response_status(resp.status);
        }
        0
    }

    /// Act on a Sharing `RESPONSE`.
    ///
    /// Only the side that sent the `INTRODUCTION` may act on this. GMS sends a
    /// `RESPONSE(ACCEPT)` of its own straight after its `INTRODUCTION` — it is the *sender*
    /// reporting that it skipped its local confirmation, logged as
    /// `[NS_TRANSFER] OutgoingPayloadTracker (…) emitted SkipLocalAcceptance(token=0016)`.
    /// Treating that as the receiver's own acceptance moved us out of
    /// [`State::AwaitingAccept`] before the user ever saw the prompt, so no accept was ever
    /// sent and GMS hung up after exactly 60 s. Measured against a Pixel 7 Pro on
    /// GMS 26.24.34.
    fn handle_response_status(&mut self, status: i32) -> i32 {
        if !self.introduction_sent {
            self.note(format!("ignoring peer RESPONSE status {status}: we did not introduce"));
            return 0;
        }
        if status == SharingResponseStatus::Accept as i32 {
            if self.state == State::Handshaking || self.state == State::AwaitingAccept {
                self.state = State::Transferring;
            }
            return 0;
        }
        let reason = if status == SharingResponseStatus::Reject as i32 {
            "peer rejected the transfer"
        } else if status == SharingResponseStatus::NotEnoughSpace as i32 {
            "peer has not enough space"
        } else if status == SharingResponseStatus::UnsupportedAttachmentType as i32 {
            "peer does not support this attachment type"
        } else if status == SharingResponseStatus::TimedOut as i32 {
            "peer timed out waiting for the user"
        } else {
            "peer sent an unknown connection response status"
        };
        self.fail(reason);
        0
    }

    /// Move to [`Phase::Ready`] once **both** halves of the paired-key exchange are done.
    ///
    /// Gated on the peer's `PAIRED_KEY_RESULT` as well as our own, matching `rquickshare`,
    /// which sends the `INTRODUCTION` only after the peer's result arrives. GMS demonstrably
    /// does send Sharing type 4 — `in Sharing type 4` appears in the verified receive trace —
    /// so an earlier revision that relaxed this gate was working around the
    /// `PayloadChunk.flags` bug, which had GMS silently drop the frames that would have
    /// prompted it.
    ///
    /// Introducing before the peer's result risks the same drop-on-arrival behaviour, since
    /// the peer's Sharing layer is not listening for an introduction yet.
    fn maybe_enter_ready(&mut self) {
        if self.phase != Phase::PairedKey {
            return;
        }
        if !self.paired_key_result_sent || !self.peer_paired_key_result_seen {
            return;
        }
        self.phase = Phase::Ready;
        if self.introduction_pending {
            self.introduction_pending = false;
            let _ = self.emit_introduction();
        }
    }

    // ------------------------------------------------------------------
    // Outbound API
    // ------------------------------------------------------------------

    /// Answer an `INTRODUCTION` with `ACCEPT` or `REJECT`.
    pub fn accept(&mut self, accept: bool, _dest_dir: &str) -> i32 {
        if self.state != State::AwaitingAccept {
            return -2;
        }
        let frame = payload::build_connection_response(accept);
        let rc = self.send_sharing(&frame);
        if rc < 0 {
            return rc;
        }
        self.accepted = Some(accept);
        if accept {
            self.state = State::Transferring;
        } else {
            self.fail("local user rejected the transfer");
        }
        0
    }

    /// Stage the files this side intends to send.
    pub fn set_pending_files_for_send(&mut self, files: Vec<FileMeta>) {
        self.files_to_send = files;
    }

    /// Queue the `INTRODUCTION` frame, deferring it until the paired-key exchange
    /// finishes if it has not yet.
    pub fn queue_introduction(&mut self) -> i32 {
        if self.files_to_send.is_empty() {
            return 0;
        }
        if self.phase != Phase::Ready {
            self.introduction_pending = true;
            return 0;
        }
        self.emit_introduction()
    }

    fn emit_introduction(&mut self) -> i32 {
        self.introduction_sent = true;
        let first_id = self.alloc_payload_id();
        // Reserve one id per file so `alloc_payload_id` cannot hand the same id to
        // a later BYTES payload.
        let extra = self.files_to_send.len().saturating_sub(1) as i64;
        self.next_payload_id = self.next_payload_id.saturating_add(extra);
        let frame = payload::build_introduction_frame(&self.files_to_send, first_id);
        for (i, f) in self.files_to_send.iter_mut().enumerate() {
            f.payload_id = first_id.saturating_add(i as i64);
        }
        self.send_sharing(&frame)
    }

    /// Emit a keep-alive so a long transfer does not look idle.
    pub fn send_keep_alive(&mut self) -> i32 {
        if self.phase != Phase::Ready && self.phase != Phase::PairedKey {
            return -2;
        }
        self.keep_alive_seq = self.keep_alive_seq.wrapping_add(1);
        let seq = self.keep_alive_seq;
        let frame = payload::build_keep_alive(false, seq);
        self.send_encrypted(&frame)
    }

    /// Begin a FILE payload.
    ///
    /// The payload id comes from the `INTRODUCTION` we already sent, so the peer can
    /// match the bytes to the metadata it showed the user. A file we never announced
    /// gets a fresh id, which a GMS peer will ignore.
    pub fn open_file(&mut self, file_name: &str, file_size: i64) -> i32 {
        if self.secure.is_none() {
            return -2;
        }
        let announced = self
            .files_to_send
            .iter()
            .find(|f| f.name == file_name)
            .map(|f| f.payload_id)
            .filter(|id| *id > 0);
        let payload_id = match announced {
            Some(id) => id,
            None => self.alloc_payload_id(),
        };
        self.active_send = Some(ActiveSend {
            payload_id,
            name: file_name.to_string(),
            total_size: file_size,
            sent_offset: 0,
        });
        0
    }

    /// Send one chunk of the open FILE payload.
    pub fn write_chunk(&mut self, chunk: &[u8]) -> i32 {
        let Some(active) = self.active_send.as_ref() else {
            return -2;
        };
        if self.secure.is_none() {
            return -2;
        }
        let payload_id = active.payload_id;
        let file_name = active.name.clone();
        let total_size = active.total_size;
        let offset = active.sent_offset;
        // `chunk_payload` splits down to the wire chunk size, so the Kotlin read
        // buffer size and the payload chunk size stay independent.
        let frames = payload::chunk_payload(payload_id, chunk, &file_name, total_size, offset);
        let mut cursor = offset;
        let mut finished = false;
        for frame in &frames {
            let body_len = frame
                .payload_chunk
                .as_ref()
                .map_or(0, |c| c.body().len() as i64);
            let is_last = frame
                .payload_chunk
                .as_ref()
                .is_some_and(frame::PayloadChunk::is_last);
            let offline = payload::wrap_payload_transfer(frame.clone());
            let rc = self.send_encrypted(&offline);
            if rc < 0 {
                return rc;
            }
            cursor = cursor.saturating_add(body_len);
            if is_last {
                finished = true;
            }
        }
        if finished {
            self.active_send = None;
            self.sent_payloads.insert(payload_id);
            self.maybe_complete_send();
        } else if let Some(active) = self.active_send.as_mut() {
            active.sent_offset = cursor;
        }
        0
    }

    /// Flip to [`State::Completed`] once every payload our `INTRODUCTION` announced has had
    /// its `FLAG_LAST` chunk written.
    ///
    /// Without this a successful send never completes: `Completed` used to be assigned only on
    /// the receive path, so after `close_file()` the pump span until the peer hung up and the
    /// `DISCONNECTION` handler then converted a finished send into a failure. Reaching
    /// `Completed` here also makes that handler a no-op, because [`Session::feed_inbound`]
    /// stops reading once the session is terminal.
    fn maybe_complete_send(&mut self) {
        if self.state != State::Transferring || self.files_to_send.is_empty() {
            return;
        }
        let all_sent = self
            .files_to_send
            .iter()
            .all(|f| f.payload_id > 0 && self.sent_payloads.contains(&f.payload_id));
        if all_sent {
            self.note(format!("all {} announced payload(s) written", self.files_to_send.len()));
            self.state = State::Completed;
        }
    }

    /// Finish the open FILE payload.
    pub fn close_file(&mut self) -> i32 {
        self.active_send = None;
        0
    }

    /// Take the oldest received FILE chunk, encoded per `PROTOCOL_CONTRACT.md` §6.
    ///
    /// `None` once the queue is empty. BYTES payloads are Sharing frames handled
    /// in-process and never appear here.
    pub fn drain_received(&mut self) -> Option<Vec<u8>> {
        let chunk = self.received_queue.pop_front()?;
        Some(encode_received_record(&chunk))
    }

    /// The specific reason this session failed, for the UI and for logcat.
    pub fn failure_reason(&self) -> Option<&str> {
        self.failed_reason.as_deref()
    }

    #[cfg(test)]
    fn is_ready(&self) -> bool {
        self.phase == Phase::Ready
    }
}
