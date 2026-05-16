package com.kpu.backend.domain.alert

data class RuleUpdateRequest(
    val companyId: String? = null,
    val monitoringId: String? = null,
    val hostName: String? = null,
    val cpuThreshold: Int = 80,
    val memoryThreshold: Int = 85,
    val diskThreshold: Int = 90,
    val diskIoThreshold: Long = 104857600,
    val userCountThreshold: Int = 10,
    val networkInThreshold: Long = 10485760,
    val networkOutThreshold: Long = 10485760,
    val durationSeconds: Int = 10
)
