package com.chophim

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChoPhimPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ChoPhimProvider())
    }
}
