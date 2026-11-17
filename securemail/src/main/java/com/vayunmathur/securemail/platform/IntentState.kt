package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.Route

/** Holds a pending deep-link/share route until Navigation consumes it. */
object IntentState {
    var navigationRoute: Route? = null
}
