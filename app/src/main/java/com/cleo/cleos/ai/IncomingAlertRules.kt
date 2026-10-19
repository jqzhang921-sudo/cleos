package com.cleo.cleos.ai

import com.cleo.cleos.data.db.IncomingCallEntity

/** The system can launch a delayed/stale full-screen intent; it must never answer a call. */
object IncomingAlertRules {
    fun title(name: String) = "语音来电 · ${name.trim().ifBlank { "TA" }}"
    fun canShow(enabled: Boolean, id: String?, row: IncomingCallEntity?, now: Long): Boolean =
        enabled && !id.isNullOrBlank() && row?.id==id && row.status=="ringing" && row.recordId!=null &&
            now>=row.createdAt && now<row.expiresAt
}
