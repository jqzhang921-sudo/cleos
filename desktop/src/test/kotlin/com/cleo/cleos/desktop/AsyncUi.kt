package com.cleo.cleos.desktop

import androidx.compose.ui.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Yield the test coroutine while real IO completes, rather than busy-spinning virtual frames. */
@OptIn(ExperimentalTestApi::class)
internal suspend fun DesktopComposeUiTest.awaitExternal(condition:()->Boolean) {
    val deadline=System.nanoTime()+10000000000L
    while(true) {
        awaitIdle()
        if(condition())break
        if(System.nanoTime()>deadline)throw AssertionError("External IO did not finish within 10 seconds")
        withContext(Dispatchers.IO){Thread.sleep(10)}
    }
    awaitIdle()
}

@OptIn(ExperimentalTestApi::class)
internal suspend fun DesktopComposeUiTest.openDesktopSettings() {
    onNodeWithContentDescription("个人菜单").performClick()
    awaitExternal {onAllNodesWithContentDescription("个人菜单设置").fetchSemanticsNodes().isNotEmpty()}
    onNodeWithContentDescription("个人菜单设置").performClick()
    awaitExternal {onAllNodesWithText("数据与备份").fetchSemanticsNodes().isNotEmpty()}
}
