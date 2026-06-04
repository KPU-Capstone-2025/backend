package com.kpu.backend.domain.alert.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Alert Webhook", description = "Prometheus AlertManager 웹훅 수신 API")
interface AlertWebhookControllerDocs {

    @Operation(
        summary = "AlertManager 웹훅 수신",
        description = "Prometheus AlertManager로부터 알람 이벤트를 수신하여 DB 저장 및 이메일 발송을 처리합니다."
    )
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "수신 성공"),
        SwaggerApiResponse(responseCode = "500", description = "처리 중 오류")
    )
    fun receiveAlert(
        payload: Map<String, Any>,
        @Parameter(description = "경로에 포함된 monitoringId (선택)") pathId: String?
    ): ResponseEntity<String>
}
