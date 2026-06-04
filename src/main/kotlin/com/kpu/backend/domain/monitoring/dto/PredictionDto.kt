package com.kpu.backend.domain.monitoring.dto

data class MetricPrediction(
    val metric: String,
    val threshold: Double,
    val currentValue: Double,
    val trend: String,               // "rising" | "falling" | "stable"
    val hoursUntilBreach: Double?,   // null = 위험 예측 없음
    val predictedAt: Double?         // unix epoch (위험 도달 예상 시각)
)

data class PredictionResult(
    val hostName: String?,
    val predictions: List<MetricPrediction>
)
