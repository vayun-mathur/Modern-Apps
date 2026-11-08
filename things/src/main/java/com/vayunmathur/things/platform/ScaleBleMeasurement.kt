package com.vayunmathur.things.platform

import com.vayunmathur.library.log.Log

/**
 * The measurement half of [ScaleBleManager]: packet dispatch, user-slot sync,
 * live-measurement and stored-record decoding, as `internal` members of the
 * manager so ScaleBleManager.kt stays under the function-count limit. Scan,
 * GATT plumbing and the write queue stay on the manager.
 */

internal fun ScaleBleManager.dispatch(value: ByteArray) {
    when (value[0].toInt() and BYTE_MASK) {
        PACKET_MEASURE -> if (isVaScale) handleVaMeasure(value) else handleMeasure(value)
        PACKET_SCALE_INFO -> handleScaleInfo(value)
        PACKET_HW_VERSION -> {
            // Holtek firmware withholds its readiness until this hardware-version packet, and
            // only then accepts the time frame.
            if (isHoltek) sendTimeSync()
        }
        PACKET_TIME_ACK -> {
            handler.removeCallbacks(timeRetry)
            if (isVaScale) {
                // A VA scale reports nothing until it has a user slot to attribute it to.
                // A pending wipe has to go first, since it invalidates any slot we hold.
                if (DeviceController.scaleResetPending()) sendDeleteAllUsers() else syncUser()
            } else {
                Log.dev(ScaleBleManager.TAG, "time frame acknowledged; starting measurement")
                handler.removeCallbacks(sendStart)
                handler.postDelayed(sendStart, ScaleBleManager.ACK_TO_START_MS)
            }
        }
        // Stored-record replay: the VA layout differs from the classic one.
        PACKET_STORED -> if (isVaScale) handleVaStored(value) else handleStored(value)
        PACKET_USER_SYNC -> handleUserSyncResult(value)
    }
}

/**
 * Claim our slot on the scale: visit the one we already hold, or register for a new one.
 *
 * Registering is what makes offline weigh-ins attributable — the scale stamps each stored
 * record with the slot it matched, so a dedicated slot is the only way to tell our readings
 * apart from the rest of the household's.
 */
internal fun ScaleBleManager.syncUser() {
    val index = DeviceController.scaleUserIndex()
    if (index == null) {
        sendUserFrame(VA_SUB_REGISTER, index = 0, key = DeviceController.scaleUserKey())
    } else {
        sendUserFrame(VA_SUB_VISIT, index = index, key = DeviceController.scaleUserKey())
    }
}

/**
 * The transient slot, used only when the scale has no room left for us. Measurements still
 * work; they just cannot be told apart from anyone else's when taken offline.
 */
internal fun ScaleBleManager.sendVisitorUser() {
    vaUserIndex = null
    sendUserFrame(VA_SUB_VISIT, VA_VISITOR_INDEX, keyHi = VA_VISITOR_KEY_HI, keyLo = VA_VISITOR_KEY_LO)
}

internal fun ScaleBleManager.sendUserFrame(
    sub: Int,
    index: Int,
    key: Int? = null,
    keyHi: Int = (key ?: 0) shr KEY_HIGH_SHIFT and BYTE_MASK,
    keyLo: Int = (key ?: 0) and BYTE_MASK,
) {
    val profile = DeviceController.scaleProfile.value
    // The wire encoding is the inverse of the SDK's own BleUser convention.
    val gender = if (profile.sex == Sex.Male) 0 else 1
    val age = profile.age.coerceIn(6, 80)
    val heightMm = (profile.heightCm.coerceIn(40.0, 240.0) * 10).toInt()
    Log.dev(ScaleBleManager.TAG, "user sync sub=$sub index=$index gender=$gender age=$age heightMm=$heightMm")
    enqueue(
        bleWriteChar,
        buildFrame(
            CMD_USER_SYNC, sub,
            index, keyHi, keyLo,
            gender, age, (heightMm shr KEY_HIGH_SHIFT) and BYTE_MASK, heightMm and BYTE_MASK,
            VA_ALGORITHM, VA_FAT_GRADE,
        ),
    )
}

/** Frees all eight slots. Ten trailing zero bytes pad it to the length the scale expects. */
internal fun ScaleBleManager.sendDeleteAllUsers() {
    Log.dev(ScaleBleManager.TAG, "resetting all scale user slots")
    enqueue(
        bleWriteChar,
        buildFrame(CMD_USER_SYNC, VA_SUB_DELETE, VA_DELETE_ALL_MASK, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    )
}

internal fun ScaleBleManager.handleUserSyncResult(v: ByteArray) {
    if (v.size < USER_SYNC_MIN_SIZE) return
    val sub = v[2].toInt() and BYTE_MASK
    val index = v[3].toInt() and BYTE_MASK
    val ok = (v[4].toInt() and BYTE_MASK) == 1
    Log.dev(ScaleBleManager.TAG, "user sync result sub=$sub index=$index ok=$ok")
    when (sub) {
        VA_SUB_REGISTER -> {
            if (!ok) {
                // The scale only fails this when all eight slots are occupied.
                Log.status(ScaleBleManager.TAG, "no free scale slots; falling back to visitor")
                sendVisitorUser()
                return
            }
            DeviceController.saveScaleUserIndex(index)
            // Registering does not make us the active user; a visit still has to follow.
            sendUserFrame(VA_SUB_VISIT, index, key = DeviceController.scaleUserKey())
        }
        VA_SUB_VISIT -> {
            if (!ok) return
            vaUserIndex = if (index == VA_VISITOR_INDEX) null else index
            requestStoredRecords()
        }
        VA_SUB_DELETE -> {
            DeviceController.onScaleResetDone()
            if (ok) syncUser() else Log.status(ScaleBleManager.TAG, "scale reset rejected")
        }
    }
}

/**
 * Ask for buffered records. The mask selects slots by bit(n); bit 0 is the unattributed
 * bucket, which we deliberately leave out — a weigh-in the scale could not match is more
 * likely to be someone else's than ours.
 */
internal fun ScaleBleManager.requestStoredRecords() {
    val mask = vaUserIndex?.let { 1 shl it } ?: 1
    enqueue(
        bleWriteChar,
        buildCmd(CMD_START, (mask shr KEY_HIGH_SHIFT) and BYTE_MASK, mask and BYTE_MASK),
    )
}

/**
 * A measurement the scale buffered while no phone was connected. Draining these is how a
 * weigh-in done without your phone still reaches Health Connect.
 */
internal fun ScaleBleManager.handleVaStored(v: ByteArray) {
    if (v.size < VA_STORED_MIN_SIZE) return
    val total = v[3].toInt() and BYTE_MASK
    if (total == 0) {
        Log.dev(ScaleBleManager.TAG, "no stored records")
        return
    }
    val index = v[4].toInt() and BYTE_MASK
    val recordUser = v[5].toInt() and BYTE_MASK
    // The mask should already have filtered these scale-side; re-check rather than risk
    // filing someone else's weigh-in, or an unattributed one (0xF0), as ours.
    val ours = vaUserIndex
    if (ours != null && recordUser != ours) {
        Log.dev(ScaleBleManager.TAG, "stored record $index/$total belongs to user $recordUser; skipped")
        return
    }
    // Timestamp is little-endian here while weight and impedance below are big-endian; that
    // asymmetry is in the reference decoder, not a mistake.
    var seconds = 0L
    for (i in 0 until TIME_BYTES) {
        seconds = seconds or
            ((v[i + VA_STORED_TIME_OFFSET].toLong() and BYTE_MASK.toLong()) shl (i * BITS_PER_BYTE))
    }
    val measuredAt = (BASE_TIME_2000_SECONDS + seconds) * MILLIS_PER_SECOND
    val now = System.currentTimeMillis()
    if (now < measuredAt || now - measuredAt > MAX_STORED_AGE_MILLIS) {
        Log.dev(ScaleBleManager.TAG, "stored record $index/$total timestamp implausible; dropped")
        return
    }
    val weight = decodeWeightByMultiplication(twoByteInt(v[10], v[11]), kgWeightRatio)
    if (weight <= 0) return
    Log.dev(ScaleBleManager.TAG, "stored record $index/$total user=$recordUser weight=$weight")
    DeviceController.onScaleHistory(
        weightKg = weight,
        r50 = fourResTwoByte2Int(v[VA_STORED_R50_HI], v[VA_STORED_R50_LO]),
        r500 = fourResTwoByte2Int(v[VA_STORED_R500_HI], v[VA_STORED_R500_LO]),
        measuredAtMillis = measuredAt,
    )
}

/** VA 0x10 frame: user index at 3, state at 4, weight at 5..6, impedance at 7..10. */
internal fun ScaleBleManager.handleVaMeasure(v: ByteArray) {
    if (v.size < VA_MEASURE_MIN_SIZE) return
    val state = v[4].toInt() and BYTE_MASK
    val weight = decodeWeightByMultiplication(twoByteInt(v[5], v[6]), kgWeightRatio)
    when (state) {
        // 0 = settling, 1 = weight locked, 18 = reading heart rate.
        VA_STATE_SETTLING, VA_STATE_LOCKED, VA_STATE_HEART_RATE ->
            if (weight > 0) DeviceController.onScaleRealtimeWeight(weight)
        VA_STATE_COMPLETE -> {
            enqueue(configChar, buildCmd(CMD_OVER, OVER_SUB_DEFAULT))
            sendIdentifyWeight(weight)
            if (v.size < VA_COMPLETE_MIN_SIZE) {
                DeviceController.onScaleMeasurement(weight, 0, 0)
                return
            }
            DeviceController.onScaleMeasurement(
                weightKg = weight,
                r50 = fourResTwoByte2Int(v[VA_R50_HI], v[VA_R50_LO]),
                r500 = fourResTwoByte2Int(v[VA_R500_HI], v[VA_R500_LO]),
            )
        }
    }
}

/**
 * Tell the scale what our slot weighs. This is the reference the firmware matches against
 * when someone weighs in with no phone around, so keeping it current is what makes offline
 * attribution — and therefore the stored-record filter — work.
 */
internal fun ScaleBleManager.sendIdentifyWeight(weightKg: Double) {
    val index = vaUserIndex ?: return
    if (!supportsIdentifyWeight || weightKg <= 0) return
    val raw = Math.round(weightKg * IDENTIFY_WEIGHT_SCALE).toInt()
    enqueue(
        bleWriteChar,
        buildFrame(
            CMD_IDENTIFY_WEIGHT,
            index,
            (raw shr KEY_HIGH_SHIFT) and BYTE_MASK,
            raw and BYTE_MASK,
        ),
    )
}

internal fun ScaleBleManager.handleScaleInfo(v: ByteArray) {
    // scaleType is echoed back in every command we send, so read it before the length check.
    if (v.size >= SCALE_INFO_MIN_SIZE) scaleType = v[SCALE_INFO_TYPE_INDEX].toInt() and BYTE_MASK
    // The reference decoder discards anything shorter than this before reading its own
    // version/precision bytes, so a truncated info packet is not trusted for the ratio either.
    if (v.size < SCALE_INFO_FULL_MIN_SIZE) return
    val lb = (v[SCALE_INFO_FLAGS_INDEX].toInt() and UNIT_LB_FLAG) == 1
    weightRatio = if (lb) WEIGHT_RATIO_LB else WEIGHT_RATIO_KG
    kgWeightRatio = if (lb) KG_RATIO_LB else KG_RATIO_KG
    if (v.size > SCALE_INFO_IDENTIFY_INDEX) {
        supportsIdentifyWeight =
            ((v[SCALE_INFO_IDENTIFY_INDEX].toInt() shr IDENTIFY_WEIGHT_BIT) and 1) == 1
    }
    // Units etc. available at v[10] bits, v[16] lbPrecision, v[17] unit mask.
    // We keep ratio for weight decode; other bytes inform display only.
    DeviceController.scaleConnectionState.value = "Connected — step on scale"
    startHandshake()
}

/**
 * Drive the scale into streaming mode: config frame, then a time frame repeated until it
 * answers 0x21, then the start command. Until this runs the scale reports nothing at all.
 */
internal fun ScaleBleManager.startHandshake() {
    val profile = DeviceController.scaleProfile.value
    val height = profile.heightCm.toInt().coerceIn(60, 220)
    val age = profile.age.coerceIn(6, 80)
    // The config frame inverts the sex encoding used everywhere else in the SDK.
    val gender = if (profile.sex == Sex.Male) 0 else 1
    Log.dev(
        ScaleBleManager.TAG,
        "handshake: scaleType=$scaleType h=$height age=$age gender=$gender holtek=$isHoltek va=$isVaScale",
    )
    if (isVaScale) {
        // The VA config frame carries display settings only; the profile goes in 0xA0 instead.
        enqueue(configChar, buildCmd(CMD_CONFIG, UNIT_KG, LIGHT_INTERVAL, 0, 0, 0))
    } else {
        enqueue(configChar, buildCmd(CMD_CONFIG, UNIT_KG, LIGHT_INTERVAL, height, age, gender))
    }
    timeRetries = 0
    handler.removeCallbacks(timeRetry)
    // Holtek waits for its own 0x14 packet before it will take the time frame.
    if (!isHoltek) handler.postDelayed(timeRetry, ScaleBleManager.CONFIG_TO_TIME_MS)
}

internal fun ScaleBleManager.sendTimeSync() {
    timeRetries = 0
    handler.removeCallbacks(timeRetry)
    handler.post(timeRetry)
}

internal fun ScaleBleManager.handleMeasure(v: ByteArray) {
    if (v.size < MEASURE_MIN_SIZE) return
    val c2 = v[MEASURE_SUB_INDEX].toInt() and BYTE_MASK
    val weight = decodeWeight(twoByteInt(v[MEASURE_WEIGHT_HI], v[MEASURE_WEIGHT_LO]), weightRatio)

    when {
        // Streaming weight while the user is still settling.
        c2 == MEASURE_STREAMING || c2 == MEASURE_STREAM_MID || c2 == MEASURE_STREAM_HIGH ->
            if (weight > 0) DeviceController.onScaleRealtimeWeight(weight)

        // Eight-electrode scales stream ten channels across two of these packets instead.
        c2 == 1 && scaleCategory == CATEGORY_EIGHT_ELECTRODE -> handleEightElectrode(v, weight)

        // Stable weight plus the four-electrode dual-frequency impedance pair. Byte 10 also
        // carries heart rate on c2 == 2, which this app has nowhere to put.
        c2 == MEASURE_STABLE || c2 == MEASURE_STABLE_HR -> {
            if (v.size < MEASURE_STABLE_MIN_SIZE) return
            enqueue(configChar, buildCmd(CMD_OVER, OVER_SUB_DEFAULT))
            DeviceController.onScaleMeasurement(
                weightKg = weight,
                r50 = fourResTwoByte2Int(v[MEASURE_R50_HI], v[MEASURE_R50_LO]),
                r500 = fourResTwoByte2Int(v[MEASURE_R500_HI], v[MEASURE_R500_LO]),
            )
        }
    }
}

/** Ten impedance channels arrive across a two-packet burst; byte 6 is count:current. */
internal fun ScaleBleManager.handleEightElectrode(v: ByteArray, weight: Double) {
    if (v.size < EIGHT_MIN_SIZE) return
    val b6 = v[EIGHT_COUNT_INDEX].toInt() and BYTE_MASK
    val count = (b6 shr NIBBLE_SHIFT) and NIBBLE_MASK
    val current = b6 and NIBBLE_MASK
    enqueue(configChar, buildCmd(CMD_OVER, OVER_SUB_DEFAULT, b6))
    if (count != current) {
        lf20k = eightDouble(v[EIGHT_LF20_HI], v[EIGHT_LF20_HI + 1])
        lf100k = eightDouble(v[EIGHT_LF100_HI], v[EIGHT_LF100_HI + 1])
        rf20k = eightDouble(v[EIGHT_RF20_HI], v[EIGHT_RF20_HI + 1])
        rf100k = eightDouble(v[EIGHT_RF100_HI], v[EIGHT_RF100_HI + 1])
        lh20k = eightDouble(v[EIGHT_LH20_HI], v[EIGHT_LH20_HI + 1])
        burstStarted = true
        return
    }
    if (!burstStarted) return
    lh100k = eightDouble(v[EIGHT_LH100_HI], v[EIGHT_LH100_HI + 1])
    rh20k = eightDouble(v[EIGHT_RH20_HI], v[EIGHT_RH20_HI + 1])
    rh100k = eightDouble(v[EIGHT_RH100_HI], v[EIGHT_RH100_HI + 1])
    t20k = eightDouble(v[EIGHT_T20_HI], v[EIGHT_T20_HI + 1])
    t100k = eightDouble(v[EIGHT_T100_HI], v[EIGHT_T100_HI + 1])
    DeviceController.onScaleMeasurement(
        weightKg = weight,
        r50 = (lh20k + rh20k).toInt(),
        r500 = (lh100k + rh100k).toInt(),
        segmental = SegmentalImpedance(
            rh20 = rh20k, lh20 = lh20k, t20 = t20k, rf20 = rf20k, lf20 = lf20k,
            rh100 = rh100k, lh100 = lh100k, t100 = t100k, rf100 = rf100k, lf100 = lf100k,
        ),
    )
    resetBurst()
}

/** Measurements the scale buffered while the phone was away, replayed on connect. */
internal fun ScaleBleManager.handleStored(v: ByteArray) {
    // Eight-electrode scales replay across a paired-packet format we don't reassemble.
    if (scaleCategory == CATEGORY_EIGHT_ELECTRODE || v.size < CLASSIC_STORED_MIN_SIZE) return
    val weight = decodeWeight(twoByteInt(v[CLASSIC_STORED_WEIGHT_HI], v[CLASSIC_STORED_WEIGHT_LO]), weightRatio)
    if (weight <= 0) return
    // The timestamp is little-endian even though weight and impedance in the same packet are
    // big-endian; that asymmetry is in the reference decoder, not a mistake here.
    var seconds = 0L
    for (i in 0 until TIME_BYTES) {
        seconds = seconds or
            ((v[i + CLASSIC_STORED_TIME_OFFSET].toLong() and BYTE_MASK.toLong()) shl (i * BITS_PER_BYTE))
    }
    val measuredAt = (BASE_TIME_2000_SECONDS + seconds) * MILLIS_PER_SECOND
    val now = System.currentTimeMillis()
    // The scale's clock free-runs, so drop replays dated in the future or over a year back.
    if (now < measuredAt || now - measuredAt > MAX_STORED_AGE_MILLIS) return
    DeviceController.onScaleHistory(
        weightKg = weight,
        r50 = fourResTwoByte2Int(v[STORED_R50_HI], v[STORED_R50_LO]),
        r500 = fourResTwoByte2Int(v[STORED_R500_HI], v[STORED_R500_LO]),
        measuredAtMillis = measuredAt,
    )
}

// Advertisement parsing and MeasureDecoder math live in ScaleBleProtocol.kt. The
// impedance helpers take the connection's encryption flag explicitly.
internal fun ScaleBleManager.fourResTwoByte2Int(b1: Byte, b2: Byte): Int =
    fourResTwoByte2Int(b1, b2, useResistanceEncrypt)

internal fun ScaleBleManager.eightDouble(b1: Byte, b2: Byte): Double =
    eightDouble(b1, b2, useResistanceEncrypt)
