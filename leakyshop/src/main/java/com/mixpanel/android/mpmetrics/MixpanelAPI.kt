package com.mixpanel.android.mpmetrics

import com.leakyshop.demo.SdkSimulator

/** Demo stand-in, NOT the Mixpanel SDK (see com.appsflyer.AppsFlyerLib). Only resolves the analytics host. */
object MixpanelAPI {
    fun track() = SdkSimulator.touch(SdkSimulator.MIXPANEL)
}
