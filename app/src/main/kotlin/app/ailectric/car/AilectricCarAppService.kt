package app.ailectric.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.ailectric.app

class AilectricCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val session = carContext.app.session
            session.start()
            val renderer = CarMapRenderer(carContext, lifecycle)
            return MainCarScreen(carContext, renderer)
        }
    }

    companion object {
        val LOCATION_PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
