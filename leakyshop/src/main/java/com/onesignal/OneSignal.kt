package com.onesignal

import com.leakyshop.demo.SdkSimulator

/** Demo stand-in, NOT the OneSignal SDK (see com.appsflyer.AppsFlyerLib). Only resolves the push host. */
object OneSignal {
    fun initWithContext() = SdkSimulator.touch(SdkSimulator.ONESIGNAL)
}
