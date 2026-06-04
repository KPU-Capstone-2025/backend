package com.kpu.backend.domain.server.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "servers")
class Server(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val companyId: Long,

    @Column(nullable = false)
    val name: String,

    val description: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
