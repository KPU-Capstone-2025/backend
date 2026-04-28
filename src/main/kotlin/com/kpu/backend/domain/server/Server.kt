package com.kpu.backend.domain.server

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "servers")
class Server(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    val companyId: Long,
    val name: String,
    val description: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
