package com.kpu.backend.domain.chat

import org.springframework.data.jpa.repository.JpaRepository

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<ChatMessage>
    fun findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId: String): List<ChatMessage>
}
