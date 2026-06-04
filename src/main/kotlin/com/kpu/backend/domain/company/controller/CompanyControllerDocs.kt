package com.kpu.backend.domain.company.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.company.dto.AgentDestination
import com.kpu.backend.domain.company.dto.CompanyRegisterRequest
import com.kpu.backend.domain.company.dto.LoginRequest
import com.kpu.backend.domain.company.dto.LoginResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Company", description = "회사 인증 및 에이전트 연결 정보 API")
interface CompanyControllerDocs {

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인 후 JWT 토큰을 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "로그인 성공"),
        SwaggerApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치")
    )
    fun login(req: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>>

    @Operation(summary = "회사 등록", description = "회사를 등록하고 AWS EC2 모니터링 인프라를 자동 프로비저닝합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "201", description = "등록 성공"),
        SwaggerApiResponse(responseCode = "500", description = "인프라 생성 실패")
    )
    fun register(req: CompanyRegisterRequest): ResponseEntity<ApiResponse<Nothing>>

    @Operation(summary = "에이전트 연결 정보 조회", description = "monitoringId와 collectorUrl을 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "조회 성공"),
        SwaggerApiResponse(responseCode = "404", description = "회사를 찾을 수 없음")
    )
    fun getAgent(
        @Parameter(description = "회사 ID", required = true) companyId: Long
    ): ResponseEntity<ApiResponse<AgentDestination>>
}
