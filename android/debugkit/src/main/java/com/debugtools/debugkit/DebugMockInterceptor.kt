package com.debugtools.debugkit

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp mock 拦截器：按 method + encodedPath 查找规则并直接返回 mock 响应。
 */
class DebugMockInterceptor(
    private val registry: MockRegistry = DebugKit.mockRegistry()
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val rule = registry.find(method = request.method, path = request.url.encodedPath)
            ?: return chain.proceed(request)

        val contentType = rule.headers["Content-Type"]?.toMediaTypeOrNull()
        val body = rule.body.toResponseBody(contentType)
        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(rule.statusCode)
            .message("Mocked by DebugMockInterceptor")
            .body(body)

        rule.headers.forEach { (name, value) ->
            responseBuilder.addHeader(name, value)
        }
        return responseBuilder.build()
    }
}
