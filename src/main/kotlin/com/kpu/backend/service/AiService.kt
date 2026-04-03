package com.kpu.backend.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class AiService(
    @Value("\${openai.api.key}") private val apiKey: String,
    @Value("\${openai.api.url}") private val apiUrl: String
) {
    private val restTemplate = RestTemplate()
    
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

        val request = HttpEntity(body, headers)

        return try {
            val response = restTemplate.postForEntity(apiUrl, request, Map::class.java)
            val choices = response.body?.get("choices") as List<Map<String, Any>>
            val message = choices[0]["message"] as Map<String, String>
            message["content"] ?: "응답을 생성할 수 없습니다."
        } catch (e: Exception) {
            "AI 서비스 연결 오류: ${e.message}"
        }
    }

    fun getAnalysisFromGPT(alertName: String, description: String): String? {
        val prompt = "장애명: $alertName, 내용: $description 분석해줘."
        val messages = listOf(
            mapOf("role" to "user", "content" to prompt)
        )
        return callGPTWithChatHistory(messages)
    }
}