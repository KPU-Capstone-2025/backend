package com.kpu.backend.dto

/**
 * 회원가입 시 클라이언트(Postman/프론트)로부터 받는 전체 데이터
 */
data class ProvisioningRequest(
    val name: String,        // 사용자 이름
    val phone: String,       // 전화번호
    val email: String,       // 로그인용 이메일
    val password: String,    // 비밀번호
    val companyName: String, // 회사 한글명 (전시용)
    val companyId: String,   // 회사 식별자 (영어/숫자, AWS 자원 이름 및 헤더값으로 사용)
    val companyIp: String? = "0.0.0.0/0" // 허용할 회사 IP (보안그룹용)
)