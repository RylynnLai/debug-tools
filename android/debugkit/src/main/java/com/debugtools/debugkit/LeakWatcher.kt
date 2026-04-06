package com.debugtools.debugkit

import android.os.SystemClock
import leakcanary.AppWatcher
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

internal class LeakWatcher {
    private val entries = CopyOnWriteArrayList<WatchedReference>()

    fun watch(label: String, target: Any) {
        AppWatcher.objectWatcher.expectWeaklyReachable(target, label)
        entries += WatchedReference(
            label = label,
            className = target.javaClass.name,
            reference = WeakReference(target),
            watchedAtUptimeMs = SystemClock.elapsedRealtime()
        )
    }

    /**
     * Called after a LeakCanary heap analysis completes.
     * Enriches matching watch entries with the leak reference path location so the
     * desktop panel can display class name and reference location in the leak table.
     */
    fun enrichFromHeapAnalysis(
        leakingClassName: String,
        location: String,
        traceSummary: String,
        analysisTimestampMs: Long
    ) {
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.className == leakingClassName && entry.source != "leakcanary") {
                entries[i] = entry.copy(
                    location = location,
                    source = "leakcanary",
                    traceSummary = traceSummary,
                    analysisTimestampMs = analysisTimestampMs
                )
                break
            }
        }
    }

    fun snapshot(): LeakSnapshot {
        val nowUptimeMs = SystemClock.elapsedRealtime()
        return LeakSnapshot(
            retainedObjectCount = AppWatcher.objectWatcher.retainedObjectCount,
            items = entries.map { entry ->
                val retained = entry.reference.get() != null
                LeakWatchSnapshot(
                    label = entry.label,
                    className = entry.className.substringAfterLast('.'),
                    location = entry.location,
                    retained = retained,
                    retainedDurationMs = if (retained) (nowUptimeMs - entry.watchedAtUptimeMs).coerceAtLeast(0L) else 0L,
                    source = entry.source,
                    traceSummary = entry.traceSummary,
                    analysisTimestampMs = entry.analysisTimestampMs
                )
            }
        )
    }
}

internal data class WatchedReference(
    val label: String,
    val className: String,
    val reference: WeakReference<Any>,
    val location: String = "",
    val watchedAtUptimeMs: Long,
    val source: String = "watch",
    val traceSummary: String = "",
    val analysisTimestampMs: Long = 0L
)

data class LeakWatchSnapshot(
    val label: String,
    val className: String,
    val location: String,
    val retained: Boolean,
    val retainedDurationMs: Long,
    val source: String,
    val traceSummary: String,
    val analysisTimestampMs: Long
)

data class LeakSnapshot(
    val retainedObjectCount: Int,
    val items: List<LeakWatchSnapshot>
)

