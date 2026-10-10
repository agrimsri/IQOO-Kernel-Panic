package com.dpdpxray.app.ui.modes

/**
 * Routes the everyday Home links to that other work adds to the nav graph. Home shows a card only when its route
 * exists in the running build, so a build without one of them never offers a broken card.
 */
object ModeRoutes {
    /** "My phone checkup". */
    const val CHECKUP = "checkup"

    /** "Why you can trust TraceVeil". */
    const val TRUST = "trust"
}
