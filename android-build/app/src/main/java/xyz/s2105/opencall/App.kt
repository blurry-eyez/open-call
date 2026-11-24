package xyz.s2105.opencall

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Net.init(this)
        CallEngine.app = this
        Rtc.init(this)
        Auth.refresh()
    }
}
