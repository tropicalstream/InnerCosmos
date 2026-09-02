package com.rayneo.innercosmos

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class InnerCosmosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registers with the RayNeo compositor so both lenses are driven.
        runCatching { MercurySDK.init(this) }
    }
}
