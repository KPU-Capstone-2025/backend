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