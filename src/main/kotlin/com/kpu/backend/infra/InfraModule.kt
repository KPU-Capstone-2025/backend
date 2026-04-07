package com.kpu.backend.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class AiService(
    @Value("\${openai.api.key}") private val apiKey: String,
    @Value("\${openai.api.url}") private val apiUrl: String,
    private val restTemplate: RestTemplate
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)

    private fun callOpenAI(messages: List<Map<String, String>>): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }
        val body = mapOf(
            "model" to "gpt-4o-mini", 
            "messages" to messages,
            "temperature" to 0.7
        )

        return try {
            val response = restTemplate.postForEntity(apiUrl, HttpEntity(body, headers), Map::class.java)
            val choices = response.body?.get("choices") as? List<Map<String, Any>>
            val message = choices?.get(0)?.get("message") as? Map<String, String>
            message?.get("content") ?: "분석 결과를 가져올 수 없습니다."
        } catch (e: Exception) {
            log.error("AI Service Error: ${e.message}")
            "AI 서비스 응답 지연 (잠시 후 다시 시도해 주세요)"
        }
    }

    // 1. [Webhook용] 장애 발생 시 단발성 분석
    fun getAnalysisFromGPT(alertName: String, description: String): String {
        val prompt = """
            당신은 서버 보안 및 운영 전문가입니다. 
            다음 장애 상황에 대해 원인과 해결 방안을 3줄 이내로 간결하게 답변하세요.
            장애명: $alertName
            내용: $description
        """.trimIndent()
        return callOpenAI(listOf(mapOf("role" to "user", "content" to prompt)))
    }

    // 2. [Chatbot용] 실시간 상태 및 로그를 포함한 답변 생성
    fun getChatResponse(systemContext: String, userQuestion: String): String {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemContext),
            mapOf("role" to "user", "content" to userQuestion)
        )
        return callOpenAI(messages)
    }
}

interface NotificationService {
    fun sendAlert(to: String, subject: String, content: String)
}

@Service
class EmailNotificationService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username}") private val fromEmail: String
) : NotificationService {
    private val log = LoggerFactory.getLogger(EmailNotificationService::class.java)

    override fun sendAlert(to: String, subject: String, content: String) {
        try {
            val message = SimpleMailMessage().apply {
                setTo(to)
                setSubject(subject)
                setText(content)
                setFrom(fromEmail)
            }
            mailSender.send(message)
            log.info("📧 이메일 발송 완료 -> $to")
        } catch (e: Exception) {
            log.error("📧 이메일 발송 실패: ${e.message}")
        }
    }
}