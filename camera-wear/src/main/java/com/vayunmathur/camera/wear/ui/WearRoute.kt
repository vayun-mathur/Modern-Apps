package com.vayunmathur.camera.wear.ui

/** Watch navigation destinations. Screen changes only ever happen through these routes. */
sealed interface WearRoute {
    /** Live viewfinder with shutter/record and mode switching. */
    data object Viewfinder : WearRoute

    /** Zoom, flash/torch, lens and timer controls (executed on the phone). */
    data object Controls : WearRoute

    /** Last-capture thumbnail (the media itself stays on the phone). */
    data object LastCapture : WearRoute
}
