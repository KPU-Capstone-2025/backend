// src/main/kotlin/com/kpu/backend/dto/RuleUpdateRequest.kt
package com.kpu.backend.dto

data class RuleUpdateRequest(
    val companyId: String,
    val cpuThreshold: Int,
    val memoryThreshold: Int,
    val networkThreshold: Long, 
    val durationSeconds: Int
)