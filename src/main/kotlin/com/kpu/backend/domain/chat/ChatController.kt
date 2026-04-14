package com.kpu.backend.domain.chat

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatService) {

    @PostMapping("/ask")
    fun chat(@RequestBody req: ChatRequest) =
        ResponseEntity.ok(ChatResponse(chatService.askQuestion(req.monitoringId, req.question)))

    @GetMapping("/history/{monitoringId}")
    fun getHistory(@PathVariable monitoringId: String) =
        ResponseEntity.ok(chatService.getChatHistory(monitoringId))
}
