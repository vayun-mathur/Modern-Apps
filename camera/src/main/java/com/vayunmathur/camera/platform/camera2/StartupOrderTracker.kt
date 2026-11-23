package com.vayunmathur.camera.platform.camera2

/**
 * Startup ordering breadcrumb for the launch-to-first-frame path.
 *
 * The Camera2 preview bind must be *requested* (the composable composed and
 * its session build started) before the background capability probes
 * (CameraX lens enumeration, extension / slo-mo probes) complete. If a
 * probe ever gates the bind again, [bindRequestedBeforeProbeCompleted]
 * flips to false and the `CameraStartup` log line shows it.
 *
 * Pure and thread-safe: the bind is recorded from composition (main), the
 * probe completion from a background dispatcher. First record of each
 * event wins, so a rebind cannot rewrite the startup ordering.
 */
class StartupOrderTracker {

    enum class Event { BIND_REQUESTED, PROBE_COMPLETED }

    private val lock = Any()
    private val recorded = mutableListOf<Event>()

    /** Records the first Camera2 bind request; false when already recorded. */
    fun recordBindRequested(): Boolean = record(Event.BIND_REQUESTED)

    /** Records the first background-probe completion; false when already recorded. */
    fun recordProbeCompleted(): Boolean = record(Event.PROBE_COMPLETED)

    private fun record(event: Event): Boolean = synchronized(lock) {
        if (event in recorded) false else {
            recorded += event
            true
        }
    }

    /** Ordered snapshot of the events recorded so far (first occurrence each). */
    fun events(): List<Event> = synchronized(lock) { recorded.toList() }

    /**
     * True when the bind was requested before the probes completed, false
     * when the probes won the race, null while either event is missing.
     */
    fun bindRequestedBeforeProbeCompleted(): Boolean? {
        val events = events()
        val bind = events.indexOf(Event.BIND_REQUESTED)
        val probe = events.indexOf(Event.PROBE_COMPLETED)
        return if (bind < 0 || probe < 0) null else bind < probe
    }
}
