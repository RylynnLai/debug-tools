package com.debugtools.debugkit

import leakcanary.EventListener
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.LeakCanary
import shark.LeakTraceReference

/**
 * Hooks into LeakCanary's heap analysis pipeline so that confirmed leak results
 * are automatically reflected back into [LeakWatcher] with class name and
 * reference-path location. This means the desktop "Memory Leak" tab can show
 * *which field/class* is holding the leaked object, not just that it is retained.
 *
 * Full LeakCanary capabilities enabled via this bridge:
 * - Automatic Activity / Fragment / Fragment-View / ViewModel / Service watching
 * - Heap dump when retained threshold is reached
 * - Heap analysis with full reference path (leak trace)
 * - Android notification with leak details
 * - Desktop panel enrichment (className + leak path location)
 */
internal object LeakCanaryBridge {

    fun install(leakWatcher: LeakWatcher) {
        // Append our listener to the existing event listener chain so default
        // behavior (notifications, logcat) is preserved.
        LeakCanary.config = LeakCanary.config.copy(
            eventListeners = LeakCanary.config.eventListeners + EventListener { event ->
                if (event is HeapAnalysisDone.HeapAnalysisSucceeded) {
                    val analysisTimestampMs = System.currentTimeMillis()
                    for (leak in event.heapAnalysis.allLeaks) {
                        for (trace in leak.leakTraces) {
                            val leakingClass = trace.leakingObject.className
                            val location = buildLocation(trace.referencePath)
                            val traceSummary = buildTraceSummary(trace.referencePath)
                            leakWatcher.enrichFromHeapAnalysis(
                                leakingClassName = leakingClass,
                                location = location,
                                traceSummary = traceSummary,
                                analysisTimestampMs = analysisTimestampMs
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * Summarises the reference path closest to the leaking object as:
     * `OwnerClass.fieldName`
     */
    private fun buildLocation(path: List<LeakTraceReference>): String {
        if (path.isEmpty()) return ""
        val ref = path.last()
        val simpleOwner = ref.owningClassName.substringAfterLast('.')
        return "$simpleOwner.${ref.referenceGenericName}"
    }

    private fun buildTraceSummary(path: List<LeakTraceReference>): String {
        if (path.isEmpty()) return ""
        return path.takeLast(3).joinToString(" -> ") { ref ->
            val owner = ref.owningClassName.substringAfterLast('.')
            "$owner.${ref.referenceGenericName}"
        }
    }
}

