package com.cleo.cleos.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

internal class EndpointTransportException(message: String) : IOException(message)

/** Only settings-confirmed model/MCP requests carry a permit. Other traffic remains HTTPS-only. */
object EndpointTransport {
    private data class Permit(val origin: String, val cleartext: Boolean)
    fun clientBuilder() = OkHttpClient.Builder().addInterceptor(Guard()).addNetworkInterceptor(Guard()).followSslRedirects(false)

    private fun parsed(raw: String): HttpUrl? = raw.trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }
    private fun origin(url: HttpUrl) = "${url.scheme}://${if (':' in url.host) "[${url.host}]" else url.host}:${url.port}"
    fun httpOrigin(raw: String): String? = parsed(raw)?.takeIf { it.scheme == "http" }?.let(::origin)

    fun prepare(request: Request, configuredUrl: String, allowHttp: Boolean): Request {
        val endpoint = parsed(configuredUrl) ?: throw EndpointTransportException("接口地址无效，请使用 HTTP 或 HTTPS 地址，不要包含用户名、密码或片段")
        if (origin(request.url) != origin(endpoint)) throw EndpointTransportException("请求地址已变化，请检查接口配置")
        if (endpoint.scheme == "http" && !allowHttp) throw EndpointTransportException("此 HTTP 地址尚未授权，请在接口设置中开启「允许 HTTP 明文连接」并确认风险")
        return request.newBuilder().tag(Permit::class.java, Permit(origin(endpoint), endpoint.scheme == "http" && allowHttp)).build()
    }

    private fun check(request: Request) {
        val permit = request.tag(Permit::class.java)
        if (permit != null && origin(request.url) != permit.origin)
            throw EndpointTransportException("接口跳转到了其他主机、端口或协议，已停止。请核实最终地址后重新配置")
        if (request.url.scheme == "http" && (permit == null || !permit.cleartext))
            throw EndpointTransportException("此请求不允许 HTTP 明文连接，请使用 HTTPS")
    }

    /** Install as BOTH application and network interceptor: check before connecting and after each redirect. */
    class Guard : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            check(request)
            val response = chain.proceed(request)
            // Check Location before OkHttp opens the next connection, not only before sending its headers.
            if (request.tag(Permit::class.java) != null && response.code in setOf(301, 302, 303, 307, 308)) {
                response.header("Location")?.let { request.url.resolve(it) }?.let { next ->
                    try { check(request.newBuilder().url(next).build()) }
                    catch (e: EndpointTransportException) { response.close(); throw e }
                }
            }
            return response
        }
    }
}
