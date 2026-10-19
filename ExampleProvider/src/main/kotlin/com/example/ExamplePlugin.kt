package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    override fun load(context: Context) {
        // Hai nguồn riêng, mỗi nguồn một tên (KKPhim Vietsub / KKPhim Thuyết minh)
        registerMainAPI(ExampleProvider("vietsub"))
        registerMainAPI(ExampleProvider("thuyetminh"))
    }
}
