package com.kpu.backend.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "chat_messages")
class ChatMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val monitoringId: String,
    val role: String, 

    @Column(columnDefinition = "TEXT")
    val content: String,

    val createdAt: LocalDateTime = LocalDateTime.now()
)