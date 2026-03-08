package com.kpu.backend.dto

data class CompanyRegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val ip: String,
    val phone: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val id: Long
)

data class AgentDestination(
    val apiKey: String,
    val collectorUrl: String
)