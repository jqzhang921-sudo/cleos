package com.cleo.cleos.ai

import java.io.FilterInputStream
import java.io.InputStream

/** Bound JSON and SSE bytes before parsing/Base64 allocation, including a single enormous SSE line. */
internal class McpPayloadStream(input: InputStream, private val limit: Long = 12L * 1024 * 1024) : FilterInputStream(input) {
    private var received = 0L
    private fun count(n: Int): Int {
        if (n > 0) {
            received += n
            if (received > limit) throw McpException("服务返回的数据超过大小限制，这次没有读取完整结果")
        }
        return n
    }
    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
}
