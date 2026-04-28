package com.kpu.backend.domain.server

import java.time.LocalDateTime

data class ServerRegisterRequest(val name: String, val description: String? = null)

data class ServerResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: LocalDateTime,
    val collectorUrl: String,
    val monitoringId: String
)
