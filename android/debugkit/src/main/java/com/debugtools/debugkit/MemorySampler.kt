package com.debugtools.debugkit

import android.os.Debug
import kotlin.math.roundToInt

object MemorySampler {
    fun sample(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return MemoryStats(
            javaUsedMb = bytesToMb(runtime.totalMemory() - runtime.freeMemory()),
            javaMaxMb = bytesToMb(runtime.maxMemory()),
            nativeHeapMb = bytesToMb(Debug.getNativeHeapAllocatedSize()),
            totalPssKb = memoryInfo.totalPss,
            nativePssKb = memoryInfo.nativePss,
            dalvikPssKb = memoryInfo.dalvikPss,
            otherPssKb = memoryInfo.otherPss
        )
    }

    private fun bytesToMb(bytes: Long): Int = (bytes / 1024f / 1024f).roundToInt()
}

data class MemoryStats(
    val javaUsedMb: Int,
    val javaMaxMb: Int,
    val nativeHeapMb: Int,
    val totalPssKb: Int,
    val nativePssKb: Int,
    val dalvikPssKb: Int,
    val otherPssKb: Int
)
