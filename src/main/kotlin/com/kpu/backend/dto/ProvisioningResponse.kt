package com.kpu.backend.dto

data class LoginRequest(
    val email: String,
    val password: String
)