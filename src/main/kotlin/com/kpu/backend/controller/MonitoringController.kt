package com.kpu.backend.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.repository.CompanyRepository
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/monitoring")
@CrossOrigin(origins = ["*"])
class MonitoringController(
    private val dashboardService: DashboardService,
    private val companyRepository: CompanyRepository
) {
    @GetMapping("/dashboard/summary")
    fun getDashboardSummary(@RequestParam companyId: String): ResponseEntity<Any> =
        ResponseEntity.ok(dashboardService.getSummary(companyId))

    @GetMapping("/logs")
    fun getLogs(@RequestParam companyId: String): ResponseEntity<Any> {
        return ResponseEntity.ok(listOf(mapOf("time" to "-", "message" to "로그 기능이 비활성화되었습니다.")))
    }

    @GetMapping("/companies")
    fun getCompanies(): ResponseEntity<Any> = ResponseEntity.ok(companyRepository.findAll())
}

@Service
class DashboardService(
    private val companyRepository: CompanyRepository,
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    private val albDns = "http://monitor-alb-1617177792.ap-northeast-2.elb.amazonaws.com:4318"
    private val restTemplate = RestTemplate()

    fun getSummary(companyId: String): Map<String, Any> {
        val cpuQuery = "sum(rate(system_cpu_time_seconds_total{company_id=\"$companyId\", state!=\"idle\"}[2m])) * 100"
        val memQuery = "sum(system_memory_usage_bytes{company_id=\"$companyId\", state=\"used\"}) / 1024 / 1024"
        
        val cpuRaw = fetchMetricsFromAlb(companyId, cpuQuery)
        val memRaw = fetchMetricsFromAlb(companyId, memQuery)
        
        val cpu = extractValue(cpuRaw) ?: 0.0
        val memMb = extractValue(memRaw) ?: 0.0
        
        return if (cpuRaw == null) {
            mapOf("systemStatus" to "LOADING", "message" to "인프라 연결 중...")
        } else {
            mapOf(
                "systemStatus" to if (cpu > 80.0) "DANGER" else "NORMAL",
                "lastUpdate" to LocalDateTime.now().toString(),
                "serverKpi" to mapOf(
                    "cpuUsage" to (Math.round(cpu * 100.0) / 100.0),
                    "memoryUsage" to (Math.round((memMb / 2048.0 * 100) * 100.0) / 100.0),
                    "networkTraffic" to "${Math.round(memMb)} MB 사용 중"
                )
            )
        }
    }

    private fun fetchMetricsFromAlb(companyId: String, query: String): String? {
        val headers = HttpHeaders()
        headers.set("X-Server-Group", companyId)
        val entity = HttpEntity<Unit>(headers)
        return try {
            val uri = UriComponentsBuilder.fromHttpUrl("$albDns/api/v1/query")
                .queryParam("query", query)
                .build().toUri()
            val response = restTemplate.exchange(uri, HttpMethod.GET, entity, String::class.java).body
            if (response != null && !response.contains("\"result\":[]")) response else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractValue(json: String?): Double? {
        if (json == null) return null
        return try {
            val root = objectMapper.readTree(json)
            val result = root.path("data").path("result")
            if (result.isArray && result.size() > 0) result.get(0).path("value").get(1).asText().toDouble() else null
        } catch (e: Exception) { null }
    }
}