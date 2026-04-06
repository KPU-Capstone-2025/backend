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

    fun callGPTWithChatHistory(messages: List<Map<String, String>>): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(apiKey)
        }
        val body = mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to messages,
            "temperature" to 0.7
        )

        return try {
            val response = restTemplate.postForEntity(apiUrl, HttpEntity(body, headers), Map::class.java)
            val choices = response.body?.get("choices") as? List<Map<String, Any>>
            val message = choices?.get(0)?.get("message") as? Map<String, String>
            message?.get("content") ?: "분석을 생성할 수 없습니다."
        } catch (e: Exception) {
            log.error("AI Service Error", e)
            "AI 서비스 연결 오류: ${e.message}"
        }
    }

    fun getAnalysisFromGPT(alertName: String, description: String): String {
        val prompt = "서버 장애 분석 요청. 장애명: $alertName, 상세내용: $description. 해결 방안을 간결하게 제시해."
        val messages = listOf(mapOf("role" to "user", "content" to prompt))
        return callGPTWithChatHistory(messages)
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
            log.info("이메일 발송 완료 -> 수신자: $to")
        } catch (e: Exception) {
            log.error("이메일 발송 실패 -> 수신자: $to, 사유: ${e.message}")
        }
    }
}