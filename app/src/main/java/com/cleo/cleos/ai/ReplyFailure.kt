package com.cleo.cleos.ai

/** The request stopped before an answer arrived. Its thought remains separate from the body. */
internal object ReplyFailure {
    fun message(error: String?, body: String, reasoning: String, network: Boolean): String? =
        if (error != null && network && body.isBlank() && reasoning.isNotBlank())
            "思考时连接中断，尚未生成回复。\n$error"
        else error
}
