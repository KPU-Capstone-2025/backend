package com.kpu.backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "companies")
class Company(
    @Id
    var id: Long = 0,
    val name: String,
    @Column(unique = true) val email: String,
    val password: String,
    val phone: String,
    val ip: String,
    val monitoringId: String,
    val collectorUrl: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)