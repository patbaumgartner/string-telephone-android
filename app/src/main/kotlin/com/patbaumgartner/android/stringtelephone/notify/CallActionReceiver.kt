package com.patbaumgartner.android.stringtelephone.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer

/** Handles the Decline action of the incoming-call notification without opening the UI. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val container = context.stringTelephoneContainer
        container.session.onCallDeclined()
        container.callEngine.reject()
    }

    companion object {
        const val ACTION_DECLINE = "com.patbaumgartner.android.stringtelephone.action.DECLINE_CALL"
    }
}
