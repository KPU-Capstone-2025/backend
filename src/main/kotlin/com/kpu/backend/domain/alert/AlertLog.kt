package com.kpu.backend.domain.alert

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "alert_logs")
class AlertLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    val monitoringId: String,
    val alertName: String,
    val severity: String,
    @Column(columnDefinition = "TEXT") val description: String,
    var aiAnalysis: String? = null,
    val hostName: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
