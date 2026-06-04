package com.kpu.backend.domain.monitoring.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.monitoring.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Dashboard", description = "서버·컨테이너 실시간 모니터링 대시보드 API")
interface DashboardControllerDocs {

    @Operation(summary = "컨테이너 목록 조회")
    fun list(companyId: Long, hostName: String?): ApiResponse<ContainerStatus>

    @Operation(summary = "호스트 현재 메트릭 조회", description = "CPU·메모리·디스크·네트워크 현재값을 반환합니다.")
    fun host(companyId: Long, hostName: String?): ApiResponse<ResourceMetrics>

    @Operation(summary = "호스트 메트릭 히스토리 조회", description = "range(분) 구간의 시계열 데이터를 반환합니다.")
    fun hostHistory(companyId: Long, range: Int, step: Int, hostName: String?): ResponseEntity<List<Map<String, Any>>>

    @Operation(summary = "발견된 호스트 목록 조회", description = "Prometheus에서 수집된 host_name 레이블 목록을 반환합니다.")
    fun hosts(companyId: Long): ResponseEntity<List<String>>

    @Operation(summary = "접속 사용자별 리소스 사용량 조회")
    fun users(companyId: Long, hostName: String?): ResponseEntity<List<UserUsageStat>>

    @Operation(summary = "이상 탐지 결과 조회", description = "Z-Score 기반 이상 탐지 결과를 반환합니다.")
    fun anomaly(companyId: Long, hostName: String?): ResponseEntity<AnomalyResult>

    @Operation(summary = "리소스 임계값 도달 예측", description = "선형회귀 기반 위험 도달 예상 시각을 반환합니다.")
    fun prediction(companyId: Long, hostName: String?): ResponseEntity<PredictionResult>

    @Operation(summary = "컨테이너별 메트릭 조회")
    fun containerMetrics(companyId: Long, containerName: String): ApiResponse<ResourceMetrics>

    @Operation(summary = "로그 조회", description = "Loki에서 severity·keyword 필터링된 로그를 반환합니다.")
    fun logs(companyId: Long, severity: String?, keyword: String?, limit: Int, hostName: String?): ApiResponse<LogEntry>

    @Operation(summary = "월별 메트릭 캘린더 조회", description = "월 단위 또는 날짜 범위로 일별 메트릭 통계를 반환합니다.")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "조회 성공")
    )
    fun monthlyMetrics(
        companyId: Long, year: Int, month: Int?,
        startDate: String?, endDate: String?, hostName: String?
    ): ApiResponse<MonthlyMetricsResponse>

    @Operation(summary = "일별 알림 원본 조회")
    fun dailyAlertRaw(companyId: Long, date: String, hostName: String?): ResponseEntity<Map<String, Any>>

    @Operation(summary = "일별 알림 AI 요약", description = "당일 알람·ERROR 로그를 AI가 분석하여 요약 텍스트를 반환합니다.")
    fun dailyAlertSummary(companyId: Long, date: String): ResponseEntity<Map<String, Any>>

    @Operation(summary = "단일 로그 AI 분석")
    @ApiResponses(
        SwaggerApiResponse(responseCode = "200", description = "분석 성공"),
        SwaggerApiResponse(responseCode = "400", description = "logContent 누락")
    )
    fun analyzeLog(request: Map<String, String>): ResponseEntity<Map<String, String>>
}
