package com.patbaumgartner.android.stringtelephone

import android.app.Application
import com.patbaumgartner.android.stringtelephone.di.AppContainer

open class StringTelephoneApp : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** Tests override this to supply fake-backed containers. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}

val android.content.Context.stringTelephoneContainer: AppContainer
    get() = (applicationContext as StringTelephoneApp).container
