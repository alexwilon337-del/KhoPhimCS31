package com.hh3d

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HH3DPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HH3DProvider())
    }
}
