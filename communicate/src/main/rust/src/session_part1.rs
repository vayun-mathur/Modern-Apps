pub fn encrypt(state: &mut SessionState, plaintext: &[u8]) -> Result<Encrypted> {
    let chain = state
        .sender_chain
        .as_ref()
        .ok_or(CryptoError("session has no sending chain"))?
        .clone();
    let keys = chain.message_keys();
    state.sender_chain = Some(chain.next());

    let ciphertext = crypto::aes_cbc_encrypt(&keys.cipher_key, &keys.iv, plaintext);
    let msg = SignalMessage::new(
        &keys.mac_key,
        &state.local_identity,
        &state.remote_identity,
        &state.sender_ratchet_public,
        chain.index,
        state.previous_counter,
        &ciphertext,
    );

    match &state.pending_pre_key {
        Some(p) => {
            let pk = PreKeySignalMessage {
                registration_id: p.local_registration_id,
                pre_key_id: p.pre_key_id,
                signed_pre_key_id: p.signed_pre_key_id,
                base_key: p.base_key,
                identity_key: state.local_identity,
                message: msg.serialized,
            };
            Ok(Encrypted { is_pre_key: true, body: pk.serialize() })
        }
        None => Ok(Encrypted { is_pre_key: false, body: msg.serialized }),
    }
}

pub fn decrypt<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SessionState,
    msg: &SignalMessage,
) -> Result<Vec<u8>> {
    // Verify-then-apply: run the whole decrypt (ratchet step, chain
    // advance, skipped-key removal) on a clone and only commit it after
    // MAC and padding verification succeed. A failed message must not
    // consume its counter or burn a skipped key / ratchet step, or the
    // genuine message with the same counter would be rejected later.
    let mut working = state.clone();
    let plaintext = decrypt_inner(rng, &mut working, msg)?;
    *state = working;
    Ok(plaintext)
}

fn decrypt_inner<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SessionState,
    msg: &SignalMessage,
) -> Result<Vec<u8>> {
    // A ratchet key we have not seen starts a new receiving chain.
    if !state.receiver_chains.iter().any(|c| c.ratchet_key == msg.ratchet_key) {
        ratchet(rng, state, &msg.ratchet_key);
    }

    let chain_idx = state
        .receiver_chains
        .iter()
        .position(|c| c.ratchet_key == msg.ratchet_key)
        .ok_or(CryptoError("no receiver chain for ratchet key"))?;

    // Replay of an already-skipped message.
    if let Some(pos) = state.receiver_chains[chain_idx]
        .skipped
        .iter()
        .position(|k| k.index == msg.counter)
    {
        let sk = state.receiver_chains[chain_idx].skipped.remove(pos);
        let keys = MessageKeys { cipher_key: sk.cipher_key, mac_key: sk.mac_key, iv: sk.iv };
        return finish_decrypt(state, msg, &keys);
    }

    let current = state.receiver_chains[chain_idx].chain_key.index;
    if msg.counter < current {
        return Err(CryptoError("duplicate or out-of-order message"));
    }
    if (msg.counter - current) as usize > MAX_MESSAGE_KEYS {
        return Err(CryptoError("message counter jumps too far ahead"));
    }

    // Skip forward, retaining the keys we stepped over.
    let mut chain = state.receiver_chains[chain_idx].chain_key.clone();
    while chain.index < msg.counter {
        let keys = chain.message_keys();
        state.receiver_chains[chain_idx].skipped.push(SkippedKey {
            index: chain.index,
            cipher_key: keys.cipher_key,
            mac_key: keys.mac_key,
            iv: keys.iv,
        });
        chain = chain.next();
    }
    let keys = chain.message_keys();
    state.receiver_chains[chain_idx].chain_key = chain.next();

    let skipped = &mut state.receiver_chains[chain_idx].skipped;
    if skipped.len() > MAX_MESSAGE_KEYS {
        let excess = skipped.len() - MAX_MESSAGE_KEYS;
        skipped.drain(0..excess);
    }

    finish_decrypt(state, msg, &keys)
}

fn finish_decrypt(
    state: &SessionState,
    msg: &SignalMessage,
    keys: &MessageKeys,
) -> Result<Vec<u8>> {
    // MAC is over remote||local from our point of view (the sender computed
    // sender||receiver, and we are the receiver).
    if !msg.verify_mac(&keys.mac_key, &state.remote_identity, &state.local_identity) {
        return Err(CryptoError("message MAC failed"));
    }
    crypto::aes_cbc_decrypt(&keys.cipher_key, &keys.iv, &msg.ciphertext)
}

/// A full DH ratchet step, triggered by a ratchet key we have not seen before.
///
/// Both halves happen together, as the Double Ratchet requires: first derive the
/// receiving chain from our *current* ratchet private key, then rotate to a fresh
/// key pair and derive the new sending chain from it.
fn ratchet<R: rand_core::Rng + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SessionState,
    their_ratchet_key: &[u8; 32],
) {
    // Close out the current sending chain so the peer can bound its skip loop.
    if let Some(c) = &state.sender_chain {
        state.previous_counter = c.index;
    }

    // Receiving half.
    let dh = crypto::agreement(&state.sender_ratchet_private, their_ratchet_key);
    let (root_key, recv_chain) = ratchet_root(&state.root_key, &dh);
    state.root_key = root_key;
    state.receiver_chains.push(ReceiverChain {
        ratchet_key: *their_ratchet_key,
        chain_key: recv_chain,
        skipped: Vec::new(),
    });
    if state.receiver_chains.len() > MAX_RECEIVER_CHAINS {
        state.receiver_chains.remove(0);
    }

    // Sending half: rotate our ratchet key pair.
    let (private, public) = crypto::generate_key_pair(rng);
    let dh = crypto::agreement(&private, their_ratchet_key);
    let (root_key, send_chain) = ratchet_root(&state.root_key, &dh);
    state.root_key = root_key;
    state.sender_ratchet_private = private;
    state.sender_ratchet_public = public;
    state.sender_chain = Some(send_chain);

    // Once the peer has replied, our messages are plain `msg` rather than `pkmsg`.
    state.pending_pre_key = None;
}
