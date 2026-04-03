package com.kpu.backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "alert_logs")
class AlertLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // 어떤 기업에서 발생했는지
    @Column(nullable = false)
    val monitoringId: String,

    // 알림 이름
    @Column(nullable = false)
    val alertName: String,

    // 위험도
    val severity: String,

    // 상세 내용(prometheus)
    @Column(columnDefinition = "TEXT")
    val description: String,

    // GPT분석 가이드
    @Column(columnDefinition = "TEXT")
    var aiAnalysis: String? = null,

    val createdAt: LocalDateTime = LocalDateTime.now()
)