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

    @PostMapping("/logs/analyze")
    fun analyzeLog(@RequestBody request: Map<String, String>): ResponseEntity<Map<String, String>> {
        val logContent = request["logContent"] ?: return ResponseEntity.badRequest().build()
        val analysis = aiService.getAnalysisFromGPT("단일 로그 분석", logContent)
        return ResponseEntity.ok(mapOf("analysis" to analysis))
    }
}
