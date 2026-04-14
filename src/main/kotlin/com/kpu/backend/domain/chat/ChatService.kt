package com.kpu.backend.domain.chat

import com.kpu.backend.domain.alert.AlertRepository
import com.kpu.backend.domain.company.CompanyRepository
import com.kpu.backend.domain.monitoring.MonitoringService
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

@Service
class ChatService(
    private val alertRepository: AlertRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val companyRepository: CompanyRepository,
    private val monitoringService: MonitoringService,
    private val chatModel: ChatLanguageModel
) {
    @Transactional
    fun askQuestion(monitoringId: String, userQuestion: String): String {
        val company = companyRepository.findByMonitoringId(monitoringId)
            ?: throw IllegalArgumentException("유효하지 않은 모니터링 ID입니다.")

        val metrics = monitoringService.getHostMetrics(company.id)
        val containers = monitoringService.getContainerList(company.id)

        val containerDetails = if (containers.isEmpty()) {
            "실행 중인 컨테이너 없음"
        } else {
            containers.joinToString("\n") {
                val cMetrics = monitoringService.getContainerMetrics(company.id, it.containerId)
                "- ${it.containerId}: CPU ${cMetrics.cpuUsage}%, 메모리 ${cMetrics.memoryUsage}MB"
            }
        }

        val recentAlerts = alertRepository.findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
        val alertContext = if (recentAlerts.isEmpty()) {
            "최근 발생한 장애 없음"
        } else {
            recentAlerts.joinToString("\n") { "[장애] ${it.alertName}: ${it.description}" }
        }

        val recentLogs = monitoringService.getLogs(company.id, null, "ERROR", null, 10)
        val logContext = if (recentLogs.isEmpty()) {
            "최근 에러 로그 없음"
        } else {
            recentLogs.joinToString("\n") { "[${it.timestamp}] ${it.body}" }
        }

        val history = chatMessageRepository
            .findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
            .take(4)
            .reversed()

        val messages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
        messages.add(SystemMessage("""
            당신은 기업 [$monitoringId] 전용 서버 관리 비서입니다.
            현재 서버 상태: CPU ${metrics.cpuUsage}%, 메모리 ${metrics.memoryUsage}%
            [컨테이너 상세]
            $containerDetails
            [최근 시스템 알람]
            $alertContext
            [최근 에러 로그 내역]
            $logContext

            위 데이터를 바탕으로 질문에 정확하게 답하세요.
        """.trimIndent()))

        history.forEach { msg ->
            if (msg.role == "user") messages.add(UserMessage(msg.content))
            else messages.add(AiMessage(msg.content))
        }
        messages.add(UserMessage(userQuestion))

        val aiResponse = chatModel.generate(messages).content().text()

        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "user", content = userQuestion))
        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "assistant", content = aiResponse))

        return aiResponse
    }

    fun getChatHistory(monitoringId: String): List<ChatHistoryResponse> {
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return chatMessageRepository.findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId)
            .map { ChatHistoryResponse(role = it.role, content = it.content, createdAt = it.createdAt.format(formatter)) }
    }
}
