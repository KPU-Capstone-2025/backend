package com.kpu.backend.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.repository.CompanyRepository
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    fun getLogs(@RequestParam companyId: String, @RequestParam(defaultValue = "100") limit: Int): ResponseEntity<Any> =
        ResponseEntity.ok(dashboardService.fetchLogs(companyId, limit))

    @GetMapping("/companies")
    fun getCompanies(): ResponseEntity<Any> = ResponseEntity.ok(companyRepository.findAll())
}

@Service
class DashboardService(
    private val companyRepository: CompanyRepository,
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    private val albDns = "http://monitor-alb-1617177792.ap-northeast-2.elb.amazonaws.com:4318" // ALB 주소
    private val restTemplate = RestTemplate()

    fun getSummary(companyId: String): Map<String, Any> {
        val cpuQuery = "sum(rate(system_cpu_time_seconds_total{company_id=\"$companyId\", state!=\"idle\"}[2m])) * 100"
        val memQuery = "sum(system_memory_usage_bytes{company_id=\"$companyId\", state=\"used\"}) / 1024 / 1024"
        
        val cpuRaw = fetchMetricsFromAlb(companyId, cpuQuery)
        val memRaw = fetchMetricsFromAlb(companyId, memQuery)
        
        val cpu = extractValue(cpuRaw) ?: 0.0
        val memMb = extractValue(memRaw) ?: 0.0
        
        return if (cpuRaw == null) {
            mapOf("systemStatus" to "LOADING", "message" to "인프라(Metrics) 서버 부팅 중입니다.")
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

    fun fetchLogs(companyId: String, limit: Int): List<Map<String, String>> {
        val headers = HttpHeaders()
        headers.set("X-Server-Group", companyId)
        val entity = HttpEntity<Unit>(headers)
        return try {
            // 💡 [중요] 주소에 /loki/ 가 1번만 들어가는지 꼭 확인하세요!
            val uri = UriComponentsBuilder.fromHttpUrl("$albDns/loki/api/v1/query_range")
                .queryParam("query", "{company_id=\"$companyId\"}")
                .queryParam("limit", limit)
                .build().toUri()

            val response = restTemplate.exchange(uri, HttpMethod.GET, entity, String::class.java).body
            val logs = parseLokiLogs(response)
            
            if (logs.isEmpty()) {
                listOf(mapOf("time" to "wait", "message" to "로그가 생성되기를 기다리는 중입니다..."))
            } else {
                logs
            }
        } catch (e: Exception) {
            listOf(mapOf("time" to "wait", "message" to "인프라(Log) 서버 부팅 중입니다..."))
        }
    }

    private fun parseLokiLogs(json: String?): List<Map<String, String>> {
        if (json == null) return emptyList()
        val logs = mutableListOf<Map<String, String>>()
        try {
            val root = objectMapper.readTree(json)
            root.path("data").path("result").forEach { stream ->
                stream.path("values").forEach { entry ->
                    val nanoTs = entry.path(0).asText().toLong()
                    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(0, nanoTs), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    logs.add(mapOf("time" to time, "message" to entry.path(1).asText()))
                }
            }
        } catch (e: Exception) { }
        return logs
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