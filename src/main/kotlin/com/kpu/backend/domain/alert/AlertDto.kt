package com.kpu.backend.domain.alert

data class RuleUpdateRequest(
    val companyId: String? = null,
    val monitoringId: String? = null,
    val cpuThreshold: Int,
    val memoryThreshold: Int,
    val diskThreshold: Int,
    val networkThreshold: Long,
    val durationSeconds: Int
)

data class AlertReceivedEvent(
    val monitoringId: String,
    val alertName: String,
    val severity: String,
    val description: String,
    val targetEmail: String,
    val companyName: String
)
