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

        val discoveredHosts = monitoringService.getDiscoveredHosts(company.id)
        val metrics = monitoringService.getHostMetrics(company.id)
        val containers = monitoringService.getContainerList(company.id)

        val containerDetails = if (containers.isEmpty()) {
            "실행 중인 컨테이너 없음"
        } else {
            containers.joinToString("\n") {
                val cMetrics = monitoringService.getContainerMetrics(company.id, it.containerId)
                "- ${it.containerId}: CPU ${String.format("%.1f", cMetrics.cpuUsage)}%, 메모리 ${String.format("%.1f", cMetrics.memoryUsage)}%"
            }
        }

        val serverDetails = if (discoveredHosts.isEmpty()) {
            "등록된 서버 없음"
        } else {
            discoveredHosts.joinToString("\n") { hostName ->
                try {
                    val m = monitoringService.getHostMetrics(company.id, hostName)
                    "- $hostName: CPU ${String.format("%.1f", m.cpuUsage)}%, 메모리 ${String.format("%.1f", m.memoryUsage)}%, 디스크 ${String.format("%.1f", m.diskUsage)}%, 상태: ${m.status}"
                } catch (e: Exception) { "- $hostName: 데이터 수집 중" }
            }
        }

        val recentAlerts = alertRepository.findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
        val alertContext = if (recentAlerts.isEmpty()) {
            "최근 발생한 임계치 초과 알람 없음"
        } else {
            recentAlerts.joinToString("\n") { "[${it.severity}] ${it.alertName} (${it.createdAt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}): ${it.description}" }
        }

        val recentLogs = monitoringService.getLogs(company.id, null, null, null, 50)
        val errorLogs = recentLogs.filter { it.severity == "ERROR" || it.severity == "WARN" }
        val logContext = if (recentLogs.isEmpty()) {
            "최근 수집된 로그 없음"
        } else {
            val errorPart = if (errorLogs.isNotEmpty()) "=== ERROR/WARN 로그 (${errorLogs.size}건) ===\n" + errorLogs.take(20).joinToString("\n") { "[${it.severity}] ${it.body}" } else "ERROR/WARN 로그 없음"
            val infoPart  = "=== 최근 INFO 로그 (참고용) ===\n" + recentLogs.filter { it.severity == "INFO" }.take(10).joinToString("\n") { "[INFO] ${it.body}" }
            "$errorPart\n\n$infoPart"
        }

        val history = chatMessageRepository
            .findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
            .take(6)
            .reversed()

        val messages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
        messages.add(SystemMessage("""
            당신은 기업 [$monitoringId] 전용 서버 관리 AI 비서입니다.
            실시간 서버 데이터를 기반으로 전문적이고 상세한 답변을 제공합니다.
            현재 총 ${discoveredHosts.size}개의 서버가 모니터링 중입니다: ${discoveredHosts.joinToString(", ")}

            ═══ 전체 서버 현황 ═══
            $serverDetails

            ═══ 전체 집계 상태 ═══
            - CPU: ${String.format("%.1f", metrics.cpuUsage)}%
            - 메모리: ${String.format("%.1f", metrics.memoryUsage)}%
            - 디스크: ${String.format("%.1f", metrics.diskUsage)}%
            - 네트워크: ${String.format("%.1f", metrics.networkTraffic)} KB/s

            ═══ 실행 중인 컨테이너 ═══
            $containerDetails

            ═══ 최근 임계치 초과 알람 (Prometheus Alertmanager) ═══
            $alertContext

            ═══ 실제 수집된 시스템 로그 (Loki, 최신순) ═══
            $logContext

            ═══ 답변 원칙 ═══
            1. 위 실제 데이터를 반드시 인용하며 답변하세요.
            2. 로그/알람 관련 질문은 위 데이터에서 구체적인 내용을 찾아 답변하세요.
            3. 특정 날짜의 로그를 물어보면 현재 보유한 최신 로그 기반으로 답변하세요.
            4. 문제 예측 질문에는: 현재 수치 → 위험 징후 → 예상 문제 → 구체적 조치 방안 순으로 답변하세요.
            5. 답변은 항목별로 구분하고, 수치를 적극 인용하여 근거를 명확히 하세요.
            6. 조치 방안은 실제 실행 가능한 명령어나 설정 변경을 포함하여 구체적으로 제시하세요.
            7. 모호하게 "확인이 필요합니다"라는 답변 금지 - 가용한 데이터로 최대한 분석하세요.
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
