package com.kpu.backend.dto

data class SignUpRequest(
    val name: String,
    val phone: String,
    val email: String,
    val password: String,
    val companyName: String,
    val companyIp: String
)