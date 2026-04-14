package com.kpu.backend.domain.chat

data class ChatRequest(val monitoringId: String, val question: String)
data class ChatResponse(val answer: String)
data class ChatHistoryResponse(val role: String, val content: String, val createdAt: String)
