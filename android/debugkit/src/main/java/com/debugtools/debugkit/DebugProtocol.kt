package com.debugtools.debugkit

import org.json.JSONObject

internal data class DebugRequest(
    val id: String,
    val type: String,
    val raw: String
)

internal object DebugProtocol {
    fun parseRequest(line: String): DebugRequest? {
        val root = parseJson(line) ?: return null
        val id = root.optString("id", "")
        val type = root.optString("type", "")
        if (id.isEmpty() || type.isEmpty()) return null
        return DebugRequest(id = id, type = type, raw = line)
    }

    fun readString(json: String, key: String): String? {
        val root = parseJson(json) ?: return null
        if (!root.has(key) || root.isNull(key)) return null
        val value = root.optString(key, "")
        return value.takeIf { it.isNotEmpty() }
    }

    fun readInt(json: String, key: String): Int? {
        val root = parseJson(json) ?: return null
        if (!root.has(key) || root.isNull(key)) return null
        val raw = root.opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    fun readStringMap(json: String, key: String): Map<String, String> {
        val root = parseJson(json) ?: return emptyMap()
        if (!root.has(key) || root.isNull(key)) return emptyMap()
        val mapJson = root.optJSONObject(key) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = mapJson.keys()
        while (keys.hasNext()) {
            val currentKey = keys.next()
            result[currentKey] = mapJson.optString(currentKey, "")
        }
        return result
    }

    private fun parseJson(raw: String): JSONObject? {
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }
}
