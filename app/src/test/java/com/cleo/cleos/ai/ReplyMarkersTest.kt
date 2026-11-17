package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class ReplyMarkersTest {
    @Test fun orphanClosingTextWrapperDoesNotAppearInOrdinaryReplyOrToolText() {
        assertEquals("慢，但没停",WakeOutput.message("慢，但没停</text>",false))
        assertEquals("慢，但没停",WakeOutput.message("慢，但没停</text>",true))
        assertEquals("All done.",ReplyMarkers.clean("All done.</TEXT>\n"))
    }
    @Test fun partialClosingWrapperNeverFlashesOrRetractsStreamingSpeech() {
        val prefix="慢，但没停";val tag="</text>";val speech=WakeOutput.Speech(false)
        val spoken=StringBuilder()
        prefix.forEach{spoken.append(speech.append(it.toString()))}
        tag.forEach{spoken.append(speech.append(it.toString()))}
        spoken.append(speech.finish());assertEquals(prefix,spoken.toString())
        for(i in 1..tag.length) assertEquals(prefix,WakeOutput.preview(prefix+tag.take(i)))
    }
    @Test fun actualMarkupAndExplicitLiteralRequestsRemainIntact() {
        listOf("<text>你好</text>","```xml\n</text>\n```","解释 </text> 这个标签","标签是 `</text>`").forEach {
            assertEquals(it,ReplyMarkers.clean(it))
        }
        val question="请原样输出 hello</text>";assertTrue(WakeOutput.allowsLiteral(question))
        assertEquals("hello</text>",WakeOutput.message("hello</text>",false,literalAllowed=WakeOutput.allowsLiteral(question)))
        assertEquals("2 <",ReplyMarkers.clean("2 <"))
    }
}
