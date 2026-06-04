package com.kpu.backend.domain.chat.controller

import com.kpu.backend.domain.chat.dto.ChatHistoryResponse
import com.kpu.backend.domain.chat.dto.ChatRequest
import com.kpu.backend.domain.chat.dto.ChatResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Chat", description = "AI 챗봇 질의 및 대화 이력 API")
interface ChatControllerDocs {

    @Operation(summary = "AI 챗봇 질문", description = "실시간 서버 데이터를 기반으로 AI 답변을 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "답변 성공"),
        SwaggerApiResponse(responseCode = "400", description = "유효하지 않은 monitoringId")
    )
    fun chat(req: ChatRequest): ResponseEntity<ChatResponse>

    @Operation(summary = "대화 이력 조회", description = "monitoringId의 전체 대화 이력을 시간순으로 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "조회 성공")
    )
    fun getHistory(
        @Parameter(description = "모니터링 ID", required = true) monitoringId: String
    ): ResponseEntity<List<ChatHistoryResponse>>
}
