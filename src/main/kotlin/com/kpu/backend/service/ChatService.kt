package com.kpu.backend.service

import com.kpu.backend.controller.ChatHistoryResponse
import com.kpu.backend.entity.ChatMessage
import com.kpu.backend.repository.AlertRepository
import com.kpu.backend.repository.ChatMessageRepository
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatService(
    private val alertRepository: AlertRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatModel: ChatLanguageModel
) {

    @Transactional
    fun askQuestion(monitoringId: String, userQuestion: String): String {
        val recentAlerts = alertRepository.findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
        val alertContext = if (recentAlerts.isEmpty()) {
            "정상 작동 중"
        } else {
            recentAlerts.joinToString("\n") { 
                "[장애] ${it.alertName}: ${it.description} (AI 분석: ${it.aiAnalysis})" }
        }

        val history: List<ChatMessage> = chatMessageRepository
            .findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
            .take(4)
            .reversed()

        val messages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
        
        messages.add(SystemMessage("""
            당신은 기업 [$monitoringId] 전용 서버 관리 비서입니다.
            현재 서버 상태: $alertContext
            이 데이터를 바탕으로 질문에 답하세요.
        """.trimIndent()))

        history.forEach { msg ->
            if (msg.role == "user") {
                messages.add(UserMessage(msg.content))
            } else {
                messages.add(AiMessage(msg.content))
            }
        }

        messages.add(UserMessage(userQuestion))

        val aiResponse = chatModel.generate(messages).content().text()

        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "user", content = userQuestion))
        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "assistant", content = aiResponse))

        return aiResponse
    }

    // 대화 내역 조회 함수
    fun getChatHistory(monitoringId: String): List<ChatHistoryResponse> {
        return chatMessageRepository.findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId)
            .map { ChatHistoryResponse(role = it.role, content = it.content) }
    }
}