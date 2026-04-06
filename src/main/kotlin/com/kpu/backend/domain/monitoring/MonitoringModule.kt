package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.company.CompanyRepository
import com.kpu.backend.domain.alert.AlertLog
import com.kpu.backend.domain.alert.AlertRepository
import com.kpu.backend.infra.AiService
import com.kpu.backend.infra.NotificationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import org.slf4j.LoggerFactory
import java.time.Instant

//Dto
data class ContainerStatus(val containerId: String, val status: String)
data class ResourceMetrics(val status: String, val cpuUsage: Double, val memoryUsage: Double, val diskUsage: Double, val networkTraffic: Double)
data class LogEntry(val timestamp: String, val severity: String, val body: String, val sourceType: String, val sourceName: String, val containerName: String?, val hostName: String?, val rawMessage: String, val interpretation: Interpretation? = null)
data class Interpretation(val title: String, val status: String, val description: String, val action: String, val evidence: List<String>)

//Service
@Service
class MonitoringService(
    private val companyRepository: CompanyRepository,
    private val restTemplate: RestTemplate,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String
) {
    fun getContainerList(companyId: Long): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElseThrow()
        val query = "container_memory_usage_bytes{container_name!=\"\"} > 0"
        return queryPrometheus(query, company.monitoringId).mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val name = metric["container_name"]?.toString()
            if (!name.isNullOrBlank()) ContainerStatus(containerId = name, status = "RUNNING") else null
        }.distinctBy { it.containerId }
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElseThrow().monitoringId
        val rx = querySingleValue("rate(system_network_rx_bytes[1m])", monId) ?: 0.0
        val tx = querySingleValue("rate(system_network_tx_bytes[1m])", monId) ?: 0.0
        return ResourceMetrics(
            status = "STABLE",
            cpuUsage = querySingleValue("system_cpu_usage", monId) ?: 0.0,
            memoryUsage = querySingleValue("system_memory_usage", monId) ?: 0.0,
            diskUsage = querySingleValue("system_disk_usage", monId) ?: 0.0,
            networkTraffic = rx + tx
        )
    }

    private fun queryPrometheus(query: String, monitoringId: String): List<Map<String, Any>> {
        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/api/v1/query")
            .queryParam("query", query).build().toUri()
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders().apply { set("X-Server-Group", monitoringId) }), Map::class.java)
            (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun querySingleValue(query: String, monitoringId: String): Double? {
        return (queryPrometheus(query, monitoringId).firstOrNull()?.get("value") as? List<*>)?.get(1)?.toString()?.toDoubleOrNull()
    }

    fun getContainerMetrics(companyId: Long, containerName: String): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElseThrow().monitoringId
        val cpuQuery = "rate(container_cpu_usage_seconds_total{container_name=\"$containerName\"}[1m]) * 100"
        val memQuery = "container_memory_usage_bytes{container_name=\"$containerName\"}"
        return ResourceMetrics(
            status = "RUNNING",
            cpuUsage = querySingleValue(cpuQuery, monId) ?: 0.0,
            memoryUsage = querySingleValue(memQuery, monId) ?: 0.0,
            diskUsage = 0.0,
            networkTraffic = 0.0
        )
    }

    fun getLogs(companyId: Long, containerName: String?, severity: String?, keyword: String?, limit: Int): List<LogEntry> {
    val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
    val monId = company.monitoringId
    val mapper = ObjectMapper()

    var logQuery = "{job=\"metric-agent\"}"
    
    if (!severity.isNullOrBlank() && severity != "all") {
        logQuery = when (severity.uppercase()) {
            "INFO" -> "$logQuery !~ \"(?i)error|warn|fault|critical\"" 
            else -> "$logQuery |= \"(?i)$severity\""
        }
    }
    if (!containerName.isNullOrBlank() && containerName != "all") logQuery = "$logQuery |= \"$containerName\""
    if (!keyword.isNullOrBlank()) logQuery = "$logQuery |= \"(?i)$keyword\""

    val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/loki/api/v1/query_range")
        .queryParam("query", logQuery)
        .queryParam("limit", limit).build().toUri()

    return try {
        val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders().apply { set("X-Server-Group", monId) }), Map::class.java)
        val data = res.body?.get("data") as? Map<*, *>
        val result = data?.get("result") as? List<Map<*, *>> ?: emptyList()

        val logs = mutableListOf<LogEntry>()
        for (streamObj in result) {
            val values = streamObj["values"] as? List<List<String>> ?: continue
            for (value in values) {
                val rawLine = value[1]
                
                var cleanBody = rawLine
                var detectedSev = "INFO"
                
                try {
                    val jsonNode = mapper.readTree(rawLine)
                    cleanBody = jsonNode.get("body")?.asText() ?: rawLine
                    detectedSev = jsonNode.get("severity")?.asText()?.uppercase() ?: "INFO"
                    val resourceNode = jsonNode.get("resources")
                    val actualContainerName = resourceNode?.get("container.name")?.asText() ?: containerName
                } catch (e: Exception) {
                    if (rawLine.contains("error", true)) detectedSev = "ERROR"
                    else if (rawLine.contains("warn", true)) detectedSev = "WARN"
                }

                logs.add(LogEntry(
                    timestamp = value[0], severity = detectedSev, body = cleanBody,
                    sourceType = "Docker", sourceName = "system", containerName = containerName,
                    hostName = monId, rawMessage = rawLine
                ))
            }
        }
        logs.sortedByDescending { it.timestamp }
    } catch (e: Exception) { emptyList() }
}
}

//Contoller(Dashboard)
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin("*")
class DashboardController(
    private val monitoringService: MonitoringService,
    private val aiService: AiService // 🌟 중요: 파라미터가 아닌 생성자로 주입 (403 방지)
) {
    @GetMapping("/container/{companyId}")
    fun list(@PathVariable companyId: Long) = ApiResponse(true, "200", "성공", containers = monitoringService.getContainerList(companyId))

    @GetMapping("/{companyId}/host")
    fun host(@PathVariable companyId: Long) = ApiResponse(true, "200", "성공", result = monitoringService.getHostMetrics(companyId))

    @GetMapping("/{companyId}/container/{containerName}/metrics")
    fun containerMetrics(@PathVariable companyId: Long, @PathVariable containerName: String) =
        ApiResponse(true, "200", "성공", result = monitoringService.getContainerMetrics(companyId, containerName))

    @GetMapping("/{companyId}/logs")
    fun logs(@PathVariable companyId: Long, 
            @RequestParam(required = false) severity: String?,
            @RequestParam(required = false) keyword: String?,
            @RequestParam(defaultValue = "100") limit: Int) =
        ApiResponse(true, "200", "성공", result = monitoringService.getLogs(companyId, null, severity, keyword, limit))

    @PostMapping("/logs/analyze")
    fun analyzeLog(@RequestBody request: Map<String, String>): ResponseEntity<Map<String, String>> {
        val logContent = request["logContent"] ?: return ResponseEntity.badRequest().build()
        val analysis = aiService.getAnalysisFromGPT("단일 로그 분석", logContent)
        return ResponseEntity.ok(mapOf("analysis" to analysis))
    }
}

//Controller(webhook)
@RestController
@RequestMapping("/api/alerts")
class MonitoringWebhookController(
    private val aiService: AiService,
    private val notificationService: NotificationService,
    private val companyRepository: CompanyRepository,
    private val alertRepository: AlertRepository
) {
    private val log = LoggerFactory.getLogger(MonitoringWebhookController::class.java)
    @PostMapping(value = ["/webhook", "/webhook/{pathId}"]) 
    fun receiveAlert(
        @RequestBody payload: Map<String, Any>,
        @PathVariable(required = false) pathId: String?
    ): ResponseEntity<String> {
        val alerts = payload["alerts"] as? List<Map<String, Any>> ?: emptyList()
        
        for (alert in alerts) {
            val labels = alert["labels"] as? Map<String, String> ?: emptyMap()
            val annotations = alert["annotations"] as? Map<String, String> ?: emptyMap()
            val status = alert["status"]?.toString() ?: payload["status"]?.toString() ?: "firing"

            val monitoringId = pathId ?: labels["company_id"] ?: "unknown"
            val alertName = labels["alertname"] ?: "UnknownAlert"
            val description = annotations["description"] ?: annotations["summary"] ?: "상세 내용 없음"
            
            val company = companyRepository.findByMonitoringId(monitoringId) ?: continue

            if (status == "firing") {
                val aiAnalysis = aiService.getAnalysisFromGPT(alertName, description)
                
                alertRepository.save(AlertLog(
                    monitoringId = monitoringId,
                    alertName = alertName,
                    description = description,
                    aiAnalysis = aiAnalysis,
                    severity = labels["severity"] ?: "critical"
                ))

                val emailContent = """
                    [${company.name}] 서버 장애 발생
                    - 장애명: $alertName
                    - 상세내용: $description
                    
                    AI 장애 분석 결과
                    $aiAnalysis
                """.trimIndent()
                
                notificationService.sendAlert(company.email, "[긴급] 서버 장애 감지: $alertName", emailContent)
                log.info("웹훅 수신: ${company.name} 에게 이메일 전송 완료")
            }
        }
        return ResponseEntity.ok("Success")
    }
}
