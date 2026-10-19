package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.IncomingCallEntity
import org.junit.Assert.*
import org.junit.Test

class IncomingAlertRulesTest {
    private val row=IncomingCallEntity("a",1,1,1,1,100,160,"private reason",1)
    @Test fun existingUsersAreNotOptedIntoLockScreenTakeover(){assertFalse(AppSettings().persistentIncomingAlerts)}
    @Test fun titleIsExplicitEvenWhenCollapsed(){assertEquals("语音来电 · 小颂",IncomingAlertRules.title(" 小颂 "));assertEquals("语音来电 · TA",IncomingAlertRules.title(" "))}
    @Test fun pendingAlertMustMatchTheExactInvitation(){assertTrue(IncomingAlertRules.canShow(true,"a",row,101));assertFalse(IncomingAlertRules.canShow(true,"b",row,101));assertFalse(IncomingAlertRules.canShow(true,null,row,101))}
    @Test fun disablingExtendedAlertsPreventsTheActivity(){assertFalse(IncomingAlertRules.canShow(false,"a",row,101))}
    @Test fun delayedIntentCannotReopenAnExpiredOrFutureCall(){assertFalse(IncomingAlertRules.canShow(true,"a",row,160));assertFalse(IncomingAlertRules.canShow(true,"a",row,99))}
    @Test fun handledOrDeletedInvitationsCannotReopen(){for(s in listOf("answered","connecting","declined","missed","cancelled","failed"))assertFalse(IncomingAlertRules.canShow(true,"a",row.copy(status=s),101));assertFalse(IncomingAlertRules.canShow(true,"a",null,101))}
    @Test fun deletedCallRecordCannotOpenThePage(){assertFalse(IncomingAlertRules.canShow(true,"a",row.copy(recordId=null),101))}
    @Test fun waitWindowIsSixtySeconds(){assertEquals(60_000L,IncomingCallRules.RING_MS)}
}
