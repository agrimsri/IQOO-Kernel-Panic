package io.sentry

import com.leakyshop.demo.SdkSimulator

/**
 * Demo stand-in, NOT the Sentry SDK (see com.appsflyer.AppsFlyerLib). Deliberately never called by LeakyShop, so the
 * SDK inventory shows a bundled SDK that was not seen during the audit.
 */
object Sentry {
    fun init() = SdkSimulator.touch(SdkSimulator.SENTRY)
}
