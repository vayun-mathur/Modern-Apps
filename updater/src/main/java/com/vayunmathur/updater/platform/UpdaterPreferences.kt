package com.vayunmathur.updater.platform

/**
 * The DataStore keys this app persists, in one place.
 *
 * The settings are read from two directions — the settings screen writes them, the background
 * job reads them — and a key spelled slightly differently in the second place is a setting that
 * silently stops being honoured. Nothing catches that: the read just returns the default.
 */
internal object UpdaterPreferences {

    /** Whether to download and apply signed OS updates without asking. Signed OS updates are on by default. */
    const val AUTO_INSTALL = "updater_auto_install"

    /** Whether auto-install may use a metered connection. Off by default — an OTA is 1-2 GB. */
    const val METERED_ALLOWED = "updater_metered_allowed"

    /** When the last check finished, epoch millis, so the UI can say how stale it is. */
    const val LAST_CHECKED = "updater_last_checked"

    /**
     * The build sitting on the inactive slot, waiting for a reboot, or absent when there is
     * none.
     *
     * Persisted because the applied slot outlives this process by hours: the payload is written,
     * everything gets killed, and something has to know that a reboot is owed.
     */
    const val PENDING_REBOOT_BUILD = "updater_pending_reboot_build"

    /**
     * Which artifact the bytes currently at `update.zip` came from.
     *
     * The package always lands at one fixed path, so the path alone cannot say whether a partial
     * file is a prefix of the artifact we are about to request. Resuming a full package onto an
     * incremental's bytes would produce a corrupt zip that only `verifyPackage` would catch,
     * after another gigabyte had been transferred.
     */
    const val DOWNLOAD_FILE = "updater_download_file"

    /**
     * An incremental that update_engine refused to initialise from.
     *
     * Without this the next run downloads the same incremental, fails the same way, and never
     * reaches the full package — an update that can never install and never stops trying.
     */
    const val FAILED_INCREMENTAL = "updater_failed_incremental"
}
