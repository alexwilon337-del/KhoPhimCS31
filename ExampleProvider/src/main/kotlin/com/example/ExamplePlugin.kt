package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    override fun load(context: Context) {
        // Hai nguồn riêng: chọn Vietsub hoặc Thuyết minh trong app
        registerMainAPI(ExampleProvider("vietsub"))
        registerMainAPI(ExampleProvider("thuyetminh"))
    }
}
