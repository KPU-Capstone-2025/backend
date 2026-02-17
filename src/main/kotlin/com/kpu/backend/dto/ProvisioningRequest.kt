package com.kpu.backend.dto

data class ProvisioningRequest(
    val companyId: String,   // 예: "kpu-test" (헤더에 들어갈 값)
    val companyName: String  // 예: "한국공대"
)