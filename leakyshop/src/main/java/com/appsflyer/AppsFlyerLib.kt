package com.appsflyer

import com.leakyshop.demo.SdkSimulator

/**
 * Demo stand-in, NOT the AppsFlyer SDK: it only borrows the real SDK's package name so `traceveil sdks` can show
 * "SDK found in APK" next to the live audit. It sends no data; it only resolves the host the real SDK contacts at start.
 */
object AppsFlyerLib {
    fun start() = SdkSimulator.touch(SdkSimulator.APPSFLYER)
}
