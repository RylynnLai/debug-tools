package com.debugtools.debugkit

import java.util.concurrent.CopyOnWriteArrayList

class HttpTrafficRegistry(
    private val maxEntries: Int = 200
) {
    private val records = CopyOnWriteArrayList<HttpTrafficRecord>()

    fun record(item: HttpTrafficRecord) {
        records += item
        while (records.size > maxEntries) {
            records.removeFirstOrNull() ?: break
        }
    }

    fun all(): List<HttpTrafficRecord> = records.toList().sortedByDescending { it.timestampMs }

    fun clear() {
        records.clear()
    }
}

data class HttpTrafficRecord(
    val timestampMs: Long,
    val method: String,
    val host: String,
    val path: String,
    val query: String,
    val statusCode: Int,
    val requestBody: String,
    val responseBody: String,
    val responseHeaders: Map<String, String>,
    val mocked: Boolean
)
