package com.kpu.backend.domain.company

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "companies")
class Company(
    @Id val id: Long = 0,
    val name: String,
    @Column(unique = true) val email: String,
    val password: String,
    val phone: String,
    val monitoringId: String,
    val collectorUrl: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
