package com.kpu.backend.domain.chat.controller

import com.kpu.backend.domain.chat.dto.ChatHistoryResponse
import com.kpu.backend.domain.chat.dto.ChatRequest
import com.kpu.backend.domain.chat.dto.ChatResponse
import com.kpu.backend.domain.chat.service.ChatServicePort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatServicePort) : ChatControllerDocs {

    @PostMapping("/ask")
    override fun chat(@RequestBody req: ChatRequest): ResponseEntity<ChatResponse> =
        ResponseEntity.ok(ChatResponse(chatService.askQuestion(req.monitoringId, req.question)))

    @GetMapping("/history/{monitoringId}")
    override fun getHistory(@PathVariable monitoringId: String): ResponseEntity<List<ChatHistoryResponse>> =
        ResponseEntity.ok(chatService.getChatHistory(monitoringId))
}
