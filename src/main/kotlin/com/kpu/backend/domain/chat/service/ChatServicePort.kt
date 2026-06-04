package com.kpu.backend.domain.chat.service

import com.kpu.backend.domain.chat.dto.ChatHistoryResponse

interface ChatServicePort {

    fun askQuestion(monitoringId: String, userQuestion: String): String

    fun getChatHistory(monitoringId: String): List<ChatHistoryResponse>
}
