package com.kpu.backend.domain.monitoring

data class ContainerStatus(val containerId: String, val status: String)

data class ResourceMetrics(
    val status: String,
    val cpuUsage: Double,
    val memoryUsage: Double,
    val diskUsage: Double,
    val networkTraffic: Double
)

data class LogEntry(
    val timestamp: String,
    val severity: String,
    val body: String,
    val sourceType: String,
    val sourceName: String,
    val containerName: String?,
    val hostName: String?,
    val rawMessage: String
)
