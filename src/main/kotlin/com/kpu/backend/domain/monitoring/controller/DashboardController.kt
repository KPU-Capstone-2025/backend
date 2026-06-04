package com.kpu.backend.domain.monitoring.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.monitoring.dto.*
import com.kpu.backend.domain.monitoring.service.AnomalyService
import com.kpu.backend.domain.monitoring.service.MonitoringService
import com.kpu.backend.domain.monitoring.service.PredictionService
import com.kpu.backend.infra.AiService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val monitoringService: MonitoringService,
    private val anomalyService: AnomalyService,
    private val predictionService: PredictionService,
    private val aiService: AiService
) : DashboardControllerDocs {

    @GetMapping("/container/{companyId}")
    override fun list(@PathVariable companyId: Long, @RequestParam(required = false) hostName: String?) =
        ApiResponse(true, "200", "성공", containers = monitoringService.getContainerList(companyId, hostName))

    @GetMapping("/{companyId}/host")
    override fun host(@PathVariable companyId: Long, @RequestParam(required = false) hostName: String?) =
        ApiResponse(true, "200", "성공", result = monitoringService.getHostMetrics(companyId, hostName))

    @GetMapping("/{companyId}/host/history")
    override fun hostHistory(
        @PathVariable companyId: Long,
        @RequestParam(defaultValue = "5") range: Int,
        @RequestParam(defaultValue = "15") step: Int,
        @RequestParam(required = false) hostName: String?
    ): ResponseEntity<List<Map<String, Any>>> =
        ResponseEntity.ok(monitoringService.getHostMetricsHistory(companyId, range, step, hostName))

    @GetMapping("/{companyId}/hosts")
    override fun hosts(@PathVariable companyId: Long): ResponseEntity<List<String>> =
        ResponseEntity.ok(monitoringService.getDiscoveredHosts(companyId))

    @GetMapping("/{companyId}/users")
    override fun users(@PathVariable companyId: Long, @RequestParam(required = false) hostName: String?): ResponseEntity<List<UserUsageStat>> =
        ResponseEntity.ok(monitoringService.getUserUsage(companyId, hostName))

    @GetMapping("/{companyId}/anomaly")
    override fun anomaly(@PathVariable companyId: Long, @RequestParam(required = false) hostName: String?): ResponseEntity<AnomalyResult> =
        ResponseEntity.ok(anomalyService.detect(companyId, hostName))

    @GetMapping("/{companyId}/prediction")
    override fun prediction(@PathVariable companyId: Long, @RequestParam(required = false) hostName: String?): ResponseEntity<PredictionResult> =
        ResponseEntity.ok(predictionService.predict(companyId, hostName))

    @GetMapping("/{companyId}/container/{containerName}/metrics")
    override fun containerMetrics(@PathVariable companyId: Long, @PathVariable containerName: String) =
        ApiResponse(true, "200", "성공", result = monitoringService.getContainerMetrics(companyId, containerName))

    @GetMapping("/{companyId}/logs")
    override fun logs(
        @PathVariable companyId: Long,
        @RequestParam(required = false) severity: String?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) hostName: String?
    ) = ApiResponse(true, "200", "성공", result = monitoringService.getLogs(companyId, null, severity, keyword, limit, hostName))

    @GetMapping("/{companyId}/metrics/monthly")
    override fun monthlyMetrics(
        @PathVariable companyId: Long,
        @RequestParam(required = false, defaultValue = "0") year: Int,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(required = false) hostName: String?
    ): ApiResponse<MonthlyMetricsResponse> {
        val resolvedYear = if (year == 0) java.time.Year.now().value else year
        return ApiResponse(true, "200", "성공",
            result = monitoringService.getMonthlyMetrics(companyId, resolvedYear, month, startDate, endDate, hostName))
    }

    @GetMapping("/{companyId}/alerts/daily/raw")
    override fun dailyAlertRaw(
        @PathVariable companyId: Long,
        @RequestParam date: String,
        @RequestParam(required = false) hostName: String?
    ): ResponseEntity<Map<String, Any>> {
        val alerts = monitoringService.getAlertsByDate(companyId, date, hostName)
        val logs   = monitoringService.getLogsByDateRange(companyId, date)
        val fmt    = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return ResponseEntity.ok(mapOf(
            "date"      to date,
            "alerts"    to alerts.map { mapOf("alertName" to it.alertName, "severity" to it.severity, "description" to it.description, "time" to it.createdAt.format(fmt)) },
            "errorLogs" to logs.take(10).map { mapOf("severity" to it.severity, "body" to it.body) }
        ))
    }

    @GetMapping("/{companyId}/alerts/daily")
    override fun dailyAlertSummary(
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
    override fun analyzeLog(@RequestBody request: Map<String, String>): ResponseEntity<Map<String, String>> {
        val logContent = request["logContent"]
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(mapOf("analysis" to aiService.getAnalysisFromGPT("단일 로그 분석", logContent)))
    }
}
