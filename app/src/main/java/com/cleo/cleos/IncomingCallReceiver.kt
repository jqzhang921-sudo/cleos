package com.cleo.cleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** Only immutable, app-created notification actions reach this non-exported receiver. */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=intent.getStringExtra(Notifier.EXTRA_INCOMING) ?: return
        val result=goAsync()
        val c=(context.applicationContext as CleosApp).container
        c.appScope.launch {
            try { c.incomingCalls.dismiss(id,if(intent.getStringExtra("outcome")=="declined") "declined" else "missed") }
            finally { result.finish() }
        }
    }
}
