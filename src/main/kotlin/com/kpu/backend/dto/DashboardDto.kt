package com.kpu.backend.dto

data class ContainerStatus(
    val containerId: String,
    val status: String
)

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
    val rawMessage: String,
    val interpretation: Interpretation? = null
)

data class Interpretation(
    val title: String,
    val status: String,     
    val description: String,
    val action: String,
    val evidence: List<String>
)