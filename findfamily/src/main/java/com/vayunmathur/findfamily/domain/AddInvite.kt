package com.vayunmathur.findfamily.domain

/** A tapped invite link: the sender's id, plus the bundle fingerprint it vouched for. */
data class AddInvite(val id: Long, val fingerprint: String?)
