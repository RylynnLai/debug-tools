package com.debugtools.debugkit

internal data class DebugRequest(
    val id: String,
    val type: String,
    val raw: String
)

internal object DebugProtocol {
    fun parseRequest(line: String): DebugRequest? {
        val id = readString(line, "id") ?: return null
        val type = readString(line, "type") ?: return null
        return DebugRequest(id = id, type = type, raw = line)
    }

    fun readString(json: String, key: String): String? {
        val token = """"$key""""
        val start = json.indexOf(token)
        if (start < 0) return null
        val colon = json.indexOf(':', start + token.length)
        val quoteStart = json.indexOf('"', colon + 1)
        val quoteEnd = json.indexOf('"', quoteStart + 1)
        if (colon < 0 || quoteStart < 0 || quoteEnd < 0) return null
        return json.substring(quoteStart + 1, quoteEnd)
    }

    fun readInt(json: String, key: String): Int? {
        val token = """"$key""""
        val start = json.indexOf(token)
        if (start < 0) return null
        val colon = json.indexOf(':', start + token.length)
        if (colon < 0) return null
        val tail = json.substring(colon + 1).trimStart()
        return tail.takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
    }

    fun readStringMap(json: String, key: String): Map<String, String> {
        val token = """"$key""""
        val start = json.indexOf(token)
        if (start < 0) return emptyMap()
        val blockStart = json.indexOf('{', start + token.length)
        val blockEnd = json.indexOf('}', blockStart + 1)
        if (blockStart < 0 || blockEnd < 0) return emptyMap()
        val block = json.substring(blockStart + 1, blockEnd)
        if (block.isBlank()) return emptyMap()
        return block.split(",").mapNotNull { entry ->
            val pair = entry.split(":")
            if (pair.size != 2) return@mapNotNull null
            val k = pair[0].trim().removePrefix("\"").removeSuffix("\"")
            val v = pair[1].trim().removePrefix("\"").removeSuffix("\"")
            k to v
        }.toMap()
    }
}
