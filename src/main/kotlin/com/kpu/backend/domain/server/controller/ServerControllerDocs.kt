package com.kpu.backend.domain.server.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.server.dto.ServerRegisterRequest
import com.kpu.backend.domain.server.dto.ServerResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Server", description = "모니터링 대상 서버 관리 API")
interface ServerControllerDocs {

    @Operation(summary = "서버 목록 조회", description = "companyId에 속한 등록 서버 목록을 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "조회 성공"),
        SwaggerApiResponse(responseCode = "404", description = "회사를 찾을 수 없음")
    )
    fun list(
        @Parameter(description = "회사 ID", required = true) companyId: Long
    ): ResponseEntity<ApiResponse<List<ServerResponse>>>

    @Operation(summary = "서버 등록", description = "새 모니터링 대상 서버를 등록합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "201", description = "등록 성공"),
        SwaggerApiResponse(responseCode = "404", description = "회사를 찾을 수 없음")
    )
    fun register(
        @Parameter(description = "회사 ID", required = true) companyId: Long,
        req: ServerRegisterRequest
    ): ResponseEntity<ApiResponse<ServerResponse>>

    @Operation(summary = "서버 삭제")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "204", description = "삭제 성공"),
        SwaggerApiResponse(responseCode = "400", description = "서버를 찾을 수 없거나 권한 없음")
    )
    fun delete(
        @Parameter(description = "회사 ID", required = true) companyId: Long,
        @Parameter(description = "서버 ID", required = true) serverId: Long
    ): ResponseEntity<ApiResponse<Nothing>>
}
