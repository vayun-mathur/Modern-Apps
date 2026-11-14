package com.vayunmathur.contacts.data

import com.vayunmathur.library.util.DatabaseItem

/** A named contacts-provider group; membership rows reference it by [id]. */
data class ContactGroup(
    override val id: Long = 0,
    val name: String
) : DatabaseItem
