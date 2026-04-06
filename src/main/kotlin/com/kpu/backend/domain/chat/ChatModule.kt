package com.kpu.backend.domain.chat

import com.kpu.backend.domain.alert.AlertRepository
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import com.kpu.backend.domain.company.CompanyRepository
import com.kpu.backend.domain.monitoring.MonitoringService

//Dto,Event,Entity
data class ChatRequest(val monitoringId: String, val question: String)
data class ChatResponse(val answer: String)
data class ChatHistoryResponse(val role: String, val content: String)

@Entity
@Table(name = "chat_messages")
class ChatMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    val monitoringId: String,
    val role: String,
    @Column(columnDefinition = "TEXT") val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId: String): List<ChatMessage>
    fun findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId: String): List<ChatMessage>
}

//Service
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
        // 1. 회사 정보 찾기
        val company = companyRepository.findByMonitoringId(monitoringId)
            ?: throw IllegalArgumentException("유효하지 않은 모니터링 ID입니다.")

        // 2.현재 메트릭과 컨테이너 정보 가져오기!
        val metrics = monitoringService.getHostMetrics(company.id)
        val containers = monitoringService.getContainerList(company.id)
        
        val containerNames = if (containers.isEmpty()) "실행 중인 컨테이너 없음" 
                                else containers.joinToString(", ") { it.containerId }

        // 3. 최근 알람 정보 가져오기
        val recentAlerts = alertRepository.findTop5ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
        val alertContext = if (recentAlerts.isEmpty()) "최근 발생한 장애 없음"
        else recentAlerts.joinToString("\n") { "[장애] ${it.alertName}: ${it.description}" }

        // 4. 이전 채팅 기록 가져오기
        val history = chatMessageRepository.findTop10ByMonitoringIdOrderByCreatedAtDesc(monitoringId)
            .take(4).reversed()

        val messages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
        
        // 5.시스템 프롬프트에 실제 데이터 주입
        messages.add(SystemMessage("""
            당신은 기업 [$monitoringId] 전용 서버 관리 비서입니다.
            현재 서버의 실제 실시간 상태는 다음과 같습니다:
            - CPU 사용량: ${metrics.cpuUsage}%
            - 메모리 사용량: ${metrics.memoryUsage}%
            - 실행 중인 컨테이너: $containerNames
            - 최근 시스템 알람: $alertContext
            이 데이터를 바탕으로 사용자의 질문에 정확하고 전문적으로 답하세요. 
            제공된 데이터에 없는 내용은 절대 지어내지 말고 "데이터를 확인할 수 없습니다"라고 대답하세요.
        """.trimIndent()))

        history.forEach { msg ->
            if (msg.role == "user") messages.add(UserMessage(msg.content))
            else messages.add(AiMessage(msg.content))
        }

        messages.add(UserMessage(userQuestion))
        
        // 6. AI 답변 생성
        val aiResponse = chatModel.generate(messages).content().text()

        // 7. 채팅 기록 저장
        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "user", content = userQuestion))
        chatMessageRepository.save(ChatMessage(monitoringId = monitoringId, role = "assistant", content = aiResponse))

        return aiResponse
    }

    fun getChatHistory(monitoringId: String): List<ChatHistoryResponse> {
        return chatMessageRepository.findAllByMonitoringIdOrderByCreatedAtAsc(monitoringId)
            .map { ChatHistoryResponse(role = it.role, content = it.content) }
    }
}

//Controller
@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatService) {
    @PostMapping("/ask")
    fun chat(@RequestBody req: ChatRequest) = ResponseEntity.ok(ChatResponse(chatService.askQuestion(req.monitoringId, req.question)))

    @GetMapping("/history/{monitoringId}")
    fun getHistory(@PathVariable monitoringId: String) = ResponseEntity.ok(chatService.getChatHistory(monitoringId))
}