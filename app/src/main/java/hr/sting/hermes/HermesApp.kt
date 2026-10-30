package hr.sting.hermes

import android.app.Application
import hr.sting.hermes.device.PushRegistration

class HermesApp : Application() {
    lateinit var core: HermesController
        private set

    override fun onCreate() {
        super.onCreate()
        core = HermesController(this)
        // Every start re-registers this phone's FCM token with the push relay: a token expires,
        // and an install updated before FCM ever called onNewToken would otherwise stay silent
        // until it did. The request is best effort and off the main thread.
        PushRegistration.refresh(this)
    }
}
