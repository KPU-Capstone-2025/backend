package com.kpu.backend.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.dto.*
import com.kpu.backend.repository.CompanyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant

@Service
class MonitoringService(
    private val companyRepository: CompanyRepository,
    private val restTemplate: RestTemplate,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String
) {
    private val promUrl = "http://$albDnsName:4318/api/v1/query"
    private val lokiUrl = "http://$albDnsName:4318/loki/api/v1/query_range"
    private val objectMapper = ObjectMapper()

    fun getContainerList(companyId: Long, freshWindowSec: Int): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElseThrow()
        val safeWindow = freshWindowSec.coerceIn(10, 3600)
        val query = "sum by (container_name) (count_over_time(container_memory_usage_bytes{container_name!=\"\"}[${safeWindow}s]))"
        val results = queryPrometheus(query, company.monitoringId)

        return results.mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val containerName = metric["container_name"]?.toString()
            val sampleCount = (it["value"] as? List<*>)?.getOrNull(1)?.toString()?.toDoubleOrNull() ?: 0.0

            if (!containerName.isNullOrBlank() && sampleCount > 0.0) {
                ContainerStatus(containerId = containerName, status = "RUNNING")
            } else null
        }.distinctBy { it.containerId }
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val monId = company.monitoringId
        
        val cpuQuery = "rate(system_cpu_usage[1m]) * 100" 
        val cpu = querySingleValue(cpuQuery, monId) ?: 0.0
        val mem = querySingleValue("system_memory_usage", monId) ?: 0.0
        val disk = querySingleValue("system_disk_usage", monId) ?: 0.0
        val rxQuery = "rate(system_network_rx_bytes[1m])"
        val txQuery = "rate(system_network_tx_bytes[1m])"
        val rx = querySingleValue(rxQuery, monId) ?: 0.0
        val tx = querySingleValue(txQuery, monId) ?: 0.0
        
        return ResourceMetrics("STABLE", cpu, mem, disk, rx + tx)
    }

    fun getContainerMetrics(companyId: Long, containerId: String, period: String): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val monId = company.monitoringId
        
        val cpuQuery = "rate(container_cpu_usage_ns{container_name='$containerId'}[1m]) / 10000000"
        val memQuery = "container_memory_usage_bytes{container_name='$containerId'}"
        val rxQuery = "rate(container_network_rx_bytes{container_name='$containerId'}[1m])"
        val txQuery = "rate(container_network_tx_bytes{container_name='$containerId'}[1m])"
        
        val cpu = querySingleValue(cpuQuery, monId) ?: 0.0
        val mem = querySingleValue(memQuery, monId) ?: 0.0
        val rx = querySingleValue(rxQuery, monId) ?: 0.0
        val tx = querySingleValue(txQuery, monId) ?: 0.0
        
        return ResourceMetrics("RUNNING", cpu, mem, 0.0, rx + tx)
    }

    fun getLogs(
        companyId: Long, containerId: String?, level: String?, keyword: String?,
        startTimeMs: Long?, endTimeMs: Long?, limit: Int
    ): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElseThrow()
        val safeLimit = limit.coerceIn(1, 500)

        var logQuery = "{job=~\"metric-agent|backend\"}" 

        if (!containerId.isNullOrBlank()) {
            logQuery += " |= \"$containerId\""
        }
        if (!level.isNullOrBlank() && level.uppercase() != "ALL") {
            logQuery += " |~ \"(?i)$level\""
        }
        if (!keyword.isNullOrBlank()) {
            logQuery += " |~ \"(?i).*$keyword.*\""
        }

        val headers = HttpHeaders().apply { set("X-Server-Group", company.monitoringId) }
        val endNs = (endTimeMs ?: Instant.now().toEpochMilli()) * 1_000_000
        val startNs = (startTimeMs ?: (Instant.now().toEpochMilli() - 15 * 60 * 1000)) * 1_000_000

        val uri = UriComponentsBuilder.fromHttpUrl(lokiUrl)
            .queryParam("query", logQuery)
            .queryParam("start", startNs)
            .queryParam("end", endNs)
            .queryParam("limit", safeLimit)
            .queryParam("direction", "backward")
            .build()
            .toUri()

        return try {
            val response = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val data = response.body?.get("data") as? Map<*, *> ?: return emptyList()
            val result = data["result"] as? List<*> ?: return emptyList()

            result.flatMap { streamObj ->
                val streamMap = streamObj as? Map<*, *> ?: return@flatMap emptyList()
                val values = streamMap["values"] as? List<*> ?: return@flatMap emptyList()
                
                values.mapNotNull { valueObj ->
                    val pair = valueObj as? List<*> ?: return@mapNotNull null
                    if (pair.size < 2) return@mapNotNull null
                    
                    val timestamp = pair[0]?.toString() ?: ""
                    val rawMessage = pair[1]?.toString() ?: ""
                    
                    parseAndAnalyzeLog(timestamp, rawMessage)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAndAnalyzeLog(timestamp: String, rawMessage: String): LogEntry {
        var body = rawMessage
        var severity = "INFO"
        var containerName: String? = null
        var hostName: String? = null

        try {
            val jsonNode = objectMapper.readTree(rawMessage)
            body = jsonNode.path("body").asText(rawMessage)
            severity = jsonNode.path("severity").asText("INFO")
            
            val attrs = jsonNode.path("attributes")
            if (!attrs.isMissingNode && attrs.has("container.name")) {
                containerName = attrs.path("container.name").asText()
            }
            
            val resources = jsonNode.path("resources")
            if (!resources.isMissingNode && resources.has("host.name")) {
                hostName = resources.path("host.name").asText()
            }
        } catch (e: Exception) {}

        val cleanBody = body.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T\\S+\\s+\\S+\\s+"), "")
        
        if (severity == "INFO" && cleanBody.contains(Regex("(?i)(error|fail|exception|timeout|denied)"))) {
            severity = "ERROR"
        }

        val sourceType = if (containerName != null) "container" else "host"
        val sourceName = containerName ?: hostName ?: "Unknown Server"
    
        val interpretation = analyzeLogContent(cleanBody, severity)

        return LogEntry(
            timestamp = timestamp,
            severity = severity,
            body = cleanBody,
            sourceType = sourceType,
            sourceName = sourceName,
            containerName = containerName,
            hostName = hostName,
            rawMessage = rawMessage,
            interpretation = interpretation
        )
    }

    private fun analyzeLogContent(body: String, severity: String): Interpretation {
        val lowerBody = body.lowercase()

        // 1. AWS SSM 권한 에러
        if (lowerBody.contains("systems manager") && lowerBody.contains("accessdenied")) {
            return Interpretation(
                title = "SSM IAM 권한 오류",
                status = "danger",
                description = "EC2 인스턴스가 AWS Systems Manager에 접근할 수 있는 IAM 역할(Role)이 없습니다.",
                action = "AWS 콘솔에서 EC2에 'AmazonSSMManagedInstanceCore' 정책이 포함된 IAM 역할을 연결해주세요.",
                evidence = listOf("Systems Manager", "AccessDeniedException")
            )
        }
        
        // 2. OOM (메모리 고갈)
        if (lowerBody.contains("outofmemory") || lowerBody.contains("oom killed")) {
            return Interpretation(
                title = "메모리 고갈 (OOM)",
                status = "danger",
                description = "컨테이너나 프로세스가 할당된 메모리를 초과 사용하여 강제 종료되었습니다.",
                action = "도커 컨테이너의 메모리 제한(Limit)을 늘리거나, 애플리케이션의 메모리 누수를 점검하세요.",
                evidence = listOf("OutOfMemory", "OOM")
            )
        }

        // 3. 404/405 에러
        if (lowerBody.contains("404") || lowerBody.contains("no such file or directory")) {
            return Interpretation(
                title = "파일/경로 찾을 수 없음 (404)",
                status = "warning",
                description = "존재하지 않는 파일이나 API 경로를 요청했습니다.",
                action = "라우팅 경로가 올바른지, 혹은 악의적인 포트 스캐닝 봇인지 확인하세요.",
                evidence = listOf("404", "No such file")
            )
        }

        // 4. 일반적인 에러/경고 폴백
        if (severity == "ERROR" || severity == "WARN") {
            return Interpretation(
                title = "시스템 예외 발생",
                status = if (severity == "ERROR") "danger" else "warning",
                description = "애플리케이션 또는 시스템 데몬에서 예기치 않은 오류나 경고가 발생했습니다.",
                action = "로그 원문을 확인하여 예외(Exception)의 스택 트레이스나 원인을 파악하세요.",
                evidence = listOf(severity)
            )
        }

        // 5. 정상 로그 상세 분류 추가
        
        if (lowerBody.contains("cron")) {
            return Interpretation(
                title = "스케줄러(CRON) 동작",
                status = "info",
                description = "서버에 등록된 정기적인 배치 작업(CRON)이 정상적으로 실행되었습니다.",
                action = "특이 사항 없음. 시스템 스케줄이 정상 수행 중입니다.",
                evidence = listOf("CRON")
            )
        }

        if (lowerBody.contains("systemd")) {
            return Interpretation(
                title = "시스템 서비스(데몬) 관리",
                status = "info",
                description = "리눅스 내부 서비스(systemd)의 시작, 종료 또는 상태 점검 기록입니다.",
                action = "특이 사항 없음. 백그라운드 서비스가 안정적으로 구동 중입니다.",
                evidence = listOf("systemd")
            )
        }

        // 🌟 6.기본 해석
        return Interpretation(
            title = "일반 상태 기록",
            status = "info",
            description = "시스템 또는 애플리케이션의 일반적인 상태 정보 및 동작 기록입니다. 현재 정상 작동 중입니다.",
            action = "특별한 조치가 필요하지 않습니다. 지속적으로 모니터링을 유지하세요.",
            evidence = listOf(if (severity.isNotBlank()) severity else "INFO")
        )
    }

    private fun querySingleValue(query: String, monitoringId: String): Double? {
        val res = queryPrometheus(query, monitoringId)
        return (res.firstOrNull()?.get("value") as? List<*>)?.get(1)?.toString()?.toDoubleOrNull()
    }

    private fun queryPrometheus(query: String, monitoringId: String): List<Map<String, Any>> {
        val headers = HttpHeaders().apply { set("X-Server-Group", monitoringId) }
        val uri = UriComponentsBuilder.fromHttpUrl(promUrl).queryParam("query", query).build().toUri()
        return try {
            val response = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val data = response.body?.get("data") as? Map<*, *>
            data?.get("result") as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) { 
            emptyList() 
        }
    }
}