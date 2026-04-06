package com.debugtools.debugkit

import java.util.concurrent.CopyOnWriteArrayList

class MockRegistry {
    private val rules = CopyOnWriteArrayList<MockRule>()

    fun upsert(rule: MockRule) {
        rules.removeAll { it.method == rule.method && it.path == rule.path }
        rules += rule
    }

    fun clear(method: String?, path: String?) {
        rules.removeAll { existing ->
            (method == null || existing.method.equals(method, ignoreCase = true)) &&
                (path == null || existing.path == path)
        }
    }

    fun find(method: String, path: String): MockRule? {
        return rules.firstOrNull {
            it.method.equals(method, ignoreCase = true) && it.path == path
        }
    }

    fun all(): List<MockRule> = rules.toList()
}

data class MockRule(
    val method: String,
    val path: String,
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>
)
