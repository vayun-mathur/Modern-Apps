@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.web.platform

import kotlin.uuid.Uuid

/** Immutable value describing permission prompt. */
data class PermissionPrompt(
    val id: String = Uuid.random().toString(),
    val origin: String,
    val types: List<SitePermissionType>,
    val onGrant: (List<SitePermissionType>) -> Unit,
    val onDeny: () -> Unit,
)
