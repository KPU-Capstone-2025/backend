package com.kpu.backend.domain.chat.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "chat_messages")
class ChatMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val monitoringId: String,

    @Column(nullable = false)
    val role: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val content: String,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
