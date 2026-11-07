package com.vayunmathur.launcher.domain

/**
 * The kinds of thing that can sit on the home screen.
 *
 * One flat table holds all of them (see `LauncherItemEntity`), so this is the
 * discriminator. Kept in `domain/` because the placement and folder rules branch on it
 * and must not depend on the persistence layer.
 */
enum class LauncherItemType {
    /** A launchable activity, addressed by package + class + user. */
    APPLICATION,

    /** A container for other items. Its own children reference it by row id. */
    FOLDER,

    /** A hosted `AppWidgetHostView`, addressed by an allocated `appWidgetId`. */
    APPWIDGET,

    /** A `ShortcutInfo` pinned from an app's static, dynamic or pinned shortcuts. */
    DEEP_SHORTCUT,
}

/**
 * Identity of an app for a given user.
 *
 * Two profiles can have the same package installed, and they are different items with
 * different icons, so the profile is part of the key everywhere. The value is the
 * `UserManager` serial number, which is the only handle for a user that survives a
 * reboot.
 */
data class PackageKey(val packageName: String, val profileSerial: Long)

/** Identity of one launchable activity of an app for a given user. */
data class ActivityKey(val packageName: String, val className: String, val profileSerial: Long)
