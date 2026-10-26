package hr.sting.hermes

import android.app.Application

class HermesApp : Application() {
    lateinit var core: HermesController
        private set

    override fun onCreate() {
        super.onCreate()
        core = HermesController(this)
    }
}
