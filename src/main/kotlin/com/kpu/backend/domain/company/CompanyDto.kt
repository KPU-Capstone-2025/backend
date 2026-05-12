package com.kpu.backend.domain.company

import com.fasterxml.jackson.annotation.JsonProperty

data class CompanyRegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String,
    val ip: String? = null
)

data class LoginRequest(val email: String, val password: String)

data class LoginResponse(
    val id: Long,
    val name: String,
    val monitoringId: String,
    val token: String
)

data class AgentDestination(
    val monitoringId: String,
    @JsonProperty("collector_url") val collectorUrl: String
)
