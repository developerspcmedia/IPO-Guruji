package com.example

import android.app.Application

class IpoApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }
}
