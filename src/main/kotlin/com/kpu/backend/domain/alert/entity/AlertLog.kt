package com.kpu.backend.domain.alert.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "alert_logs")
class AlertLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val monitoringId: String,

    @Column(nullable = false)
    val alertName: String,

    @Column(nullable = false)
    val severity: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val description: String,

    var aiAnalysis: String? = null,

    val hostName: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
