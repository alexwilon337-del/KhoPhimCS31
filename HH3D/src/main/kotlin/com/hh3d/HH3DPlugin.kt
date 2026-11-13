package com.hh3d

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HH3DPlugin : Plugin() {
    override fun load(context: Context) {
        // Hai nguồn riêng: chọn Vietsub hoặc Thuyết minh trong app
        registerMainAPI(HH3DProvider("vietsub"))
        registerMainAPI(HH3DProvider("thuyetminh"))
    }
}
