package com.kpu.backend.domain.monitoring.dto

data class MetricAnomaly(
    val metric: String,
    val current: Double,
    val mean: Double,
    val stddev: Double,
    val isAnomaly: Boolean,
    val severity: String   // "normal" | "warning" | "critical"
)

data class AnomalyResult(
    val hostName: String?,
    val detectedAt: String,
    val anomalies: List<MetricAnomaly>
)
