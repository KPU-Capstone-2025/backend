package com.kpu.backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "companies")
class Company(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 회원가입 시 받은 정보
    val name: String,
    val phone: String,
    val email: String,
    val password: String, // 실제 서비스 시에는 암호화 필요
    val companyName: String,
    
    @Column(unique = true, nullable = false)
    val companyId: String,

    // AWS 인프라 정보
    val instanceId: String,
    val targetGroupArn: String,
    val albRuleArn: String,
    val priority: Int,

    val createdAt: LocalDateTime = LocalDateTime.now()
)