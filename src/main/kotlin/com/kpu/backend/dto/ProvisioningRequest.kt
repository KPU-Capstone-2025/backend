package com.kpu.backend.dto

data class AuthResponse(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any?>? = null
)
