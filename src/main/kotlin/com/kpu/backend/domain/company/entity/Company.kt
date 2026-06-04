package com.kpu.backend.domain.company.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "companies")
class Company(
    @Id
    val id: Long = 0,

    @Column(nullable = false)
    val name: String,

    @Column(unique = true, nullable = false)
    val email: String,

    @Column(nullable = false)
    val password: String,

    @Column(nullable = false)
    val phone: String,

    @Column(nullable = false, unique = true)
    val monitoringId: String,

    @Column(nullable = false)
    val collectorUrl: String,

    val ip: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
