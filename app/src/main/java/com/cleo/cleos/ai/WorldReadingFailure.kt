package com.cleo.cleos.ai

import kotlinx.coroutines.TimeoutCancellationException

/** Public categories only. Provider prose may contain the source, reasoning or credentials. */
class WorldReplyException(val code: String, message: String) : IllegalArgumentException(message)

data class WorldFailure(val code: String, val message: String) {
    companion object {
        fun of(error: Exception): WorldFailure = when (error) {
            is WorldReplyException -> WorldFailure(error.code, error.message ?: "模型没有返回完整的阅读结果")
            is TimeoutCancellationException -> WorldFailure("timeout", "阅读请求等待超时，可以稍后重试")
            is ChatException -> when (error.status) {
                401 -> WorldFailure("auth", "接口未通过密钥验证，请检查这位 TA 的模型设置")
                403 -> WorldFailure("permission", "接口拒绝访问，请检查账号或模型权限")
                402 -> WorldFailure("balance", "接口账户余额不足，请检查模型平台账户")
                429 -> WorldFailure("rate_limit", "接口限流或额度不足，请稍后重试或检查平台额度")
                in 500..599 -> WorldFailure("server", "模型服务暂时出错（HTTP ${error.status}），可以稍后重试")
                null -> WorldFailure("network", "模型连接未完成，请检查网络和接口地址后重试")
                else -> WorldFailure("http", "接口拒绝了阅读请求（HTTP ${error.status}），请检查模型与接口配置")
            }
            else -> WorldFailure("invalid_result", "这次没有得到可确认的阅读结果，可以重试")
        }
    }
}
