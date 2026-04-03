package com.kpu.backend.controller

import com.kpu.backend.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    @PostMapping("/ask")
    fun chat(@RequestBody req: ChatRequest): ResponseEntity<ChatResponse> {
        val answer = chatService.askQuestion(req.monitoringId, req.question)
        return ResponseEntity.ok(ChatResponse(answer))
    }

    @GetMapping("/history/{monitoringId}")
    fun getHistory(@PathVariable monitoringId: String): ResponseEntity<List<ChatHistoryResponse>> {
        val history = chatService.getChatHistory(monitoringId)
        return ResponseEntity.ok(history)
    }
}
data class ChatRequest(val monitoringId: String, val question: String)
data class ChatResponse(val answer: String)
data class ChatHistoryResponse(val role: String, val content: String)