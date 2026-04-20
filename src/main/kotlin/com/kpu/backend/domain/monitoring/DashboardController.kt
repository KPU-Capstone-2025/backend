package com.kpu.backend.domain.monitoring

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.infra.AiService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin("*")
class DashboardController(
    private val monitoringService: MonitoringService,
    private val aiService: AiService
) {
    @GetMapping("/container/{companyId}")
    fun list(@PathVariable companyId: Long) =
        ApiResponse(true, "200", "성공", containers = monitoringService.getContainerList(companyId))

    @GetMapping("/{companyId}/host")
    fun host(@PathVariable companyId: Long) =
        ApiResponse(true, "200", "성공", result = monitoringService.getHostMetrics(companyId))

    @GetMapping("/{companyId}/container/{containerName}/metrics")
    fun containerMetrics(@PathVariable companyId: Long, @PathVariable containerName: String) =
        ApiResponse(true, "200", "성공", result = monitoringService.getContainerMetrics(companyId, containerName))

    @GetMapping("/{companyId}/logs")
    fun logs(
        @PathVariable companyId: Long,
        @RequestParam(required = false) severity: String?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ) = ApiResponse(true, "200", "성공", result = monitoringService.getLogs(companyId, null, severity, keyword, limit))

    /**
     * 월간 일별 리소스 집계
     *
     * 사용 예:
     *   GET /api/dashboard/{companyId}/metrics/monthly?year=2026&month=4
     *   GET /api/dashboard/{companyId}/metrics/monthly?year=2026&startDate=2026-04-01&endDate=2026-04-30
     */
    @GetMapping("/{companyId}/metrics/monthly")
    fun monthlyMetrics(
        @PathVariable companyId: Long,
        @RequestParam(required = false, defaultValue = "0") year: Int,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): ApiResponse<MonthlyMetricsResponse> {
        // year 기본값: 현재 연도
        val resolvedYear = if (year == 0) java.time.Year.now().value else year
        val result = monitoringService.getMonthlyMetrics(
            companyId   = companyId,
            year        = resolvedYear,
            month       = month,
            startDate   = startDate,
            endDate     = endDate
        )
        return ApiResponse(true, "200", "성공", result = result)
    }

    @GetMapping("/{companyId}/alerts/daily")
    fun dailyAlertSummary(
        @PathVariable companyId: Long,
        @RequestParam date: String
    ): ResponseEntity<Map<String, Any>> {
        val alerts = monitoringService.getAlertsByDate(companyId, date)
        val logs   = monitoringService.getLogsByDateRange(companyId, date)
        val fmt    = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

        val alertText = if (alerts.isEmpty()) "임계치 초과 알람 없음"
            else alerts.joinToString("\n") { "[${it.severity}] ${it.alertName}: ${it.description}" }
        val logText = if (logs.isEmpty()) "ERROR/WARN 로그 없음"
            else logs.take(30).joinToString("\n") { "[${it.severity}] ${it.body}" }

        val summary = aiService.getAnalysisFromGPT(
            "$date 서버 위험 로그 요약 분석",
            "=== 알람 내역 ===\n$alertText\n\n=== ERROR/WARN 로그 ===\n$logText\n\n위 내용을 바탕으로 해당 날짜의 서버 상태를 요약하고 주요 문제와 조치 방안을 알려주세요."
        )

        return ResponseEntity.ok(mapOf(
            "date"      to date,
            "alerts"    to alerts.map { mapOf("alertName" to it.alertName, "severity" to it.severity, "description" to it.description, "time" to it.createdAt.format(fmt)) },
            "errorLogs" to logs.take(10).map { mapOf("severity" to it.severity, "body" to it.body) },
            "summary"   to summary
        ))
    }

    @PostMapping("/logs/analyze")
    fun analyzeLog(@RequestBody request: Map<String, String>): ResponseEntity<Map<String, String>> {
        val logContent = request["logContent"] ?: return ResponseEntity.badRequest().build()
        val analysis = aiService.getAnalysisFromGPT("단일 로그 분석", logContent)
        return ResponseEntity.ok(mapOf("analysis" to analysis))
    }
}
