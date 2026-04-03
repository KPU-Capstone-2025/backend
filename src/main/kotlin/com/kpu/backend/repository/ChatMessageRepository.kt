package com.kpu.backend.repository

import com.kpu.backend.entity.ChatMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    // 챗봇 컨텍스트용 (최근 10개)
    fun findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<ChatMessage>
    
    // UI 대화내역 출력용
    fun findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId: String): List<ChatMessage>
}