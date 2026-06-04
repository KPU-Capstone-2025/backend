package com.kpu.backend.domain.alert.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.alert.dto.AlertRuleSettingResponse
import com.kpu.backend.domain.alert.dto.RuleUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Alert Rules", description = "알림 규칙(임계값) 관리 API")
interface AlertControllerDocs {

    @Operation(
        summary = "알림 규칙 업데이트",
        description = "호스트별 CPU·메모리·디스크·네트워크 임계값을 저장하고 Prometheus Alert Rules를 즉시 배포합니다."
    )
    @ApiResponses(
        SwaggerApiResponse(responseCode = "201", description = "규칙 업데이트 성공"),
        SwaggerApiResponse(responseCode = "400", description = "요청 파라미터 오류 (companyId/monitoringId 누락 또는 형식 불일치)"),
        SwaggerApiResponse(responseCode = "404", description = "모니터링 서버 또는 회사를 찾을 수 없음"),
        SwaggerApiResponse(responseCode = "500", description = "Prometheus 룰 배포 실패")
    )
    fun update(request: RuleUpdateRequest): ResponseEntity<ApiResponse<Nothing>>

    @Operation(
        summary = "알림 규칙 조회",
        description = "companyId와 선택적 hostName으로 현재 저장된 임계값을 조회합니다. 설정이 없으면 기본값을 반환합니다."
    )
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "조회 성공"),
        SwaggerApiResponse(responseCode = "400", description = "companyId 형식 오류"),
        SwaggerApiResponse(responseCode = "500", description = "서버 내부 오류")
    )
    fun getRules(
        @Parameter(description = "회사 ID", required = true) companyId: Long,
        @Parameter(description = "호스트명 (미입력 시 전체 기본값 반환)") hostName: String?
    ): ResponseEntity<ApiResponse<AlertRuleSettingResponse>>
}
