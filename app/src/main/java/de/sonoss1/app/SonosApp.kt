package de.sonoss1.app

import android.app.Application

class SonosApp : Application() {
    val repository: SonosRepository by lazy { SonosRepository(this) }
}
