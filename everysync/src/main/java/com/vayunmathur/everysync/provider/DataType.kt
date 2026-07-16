package com.vayunmathur.everysync.provider

/** The three domains EverySync can land into on-device system providers. */
enum class DataType {
    CONTACTS,
    CALENDAR,
    HEALTH,
}

/** How an account authenticates against its provider. */
enum class AuthType {
    /** OAuth2 Authorization Code + PKCE via Custom Tabs (Google, Google Health). */
    OAUTH,

    /** CalDAV / CardDAV with a base URL + app-specific password (generic, Apple/iCloud). */
    DAV,

    /** No remote auth: data already lives in Health Connect (Samsung / Google Health). */
    HEALTH_CONNECT,
}

/** Direction a sync runs in. */
enum class SyncDirection {
    PULL,
    PUSH,
    BOTH,
}
