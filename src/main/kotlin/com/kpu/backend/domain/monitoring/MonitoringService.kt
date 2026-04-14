package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.domain.company.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class MonitoringService(
    private val companyRepository: CompanyRepository,
    private val restTemplate: RestTemplate,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String
) {
    private val log = LoggerFactory.getLogger(MonitoringService::class.java)
    private val mapper = ObjectMapper()

    fun getContainerList(companyId: Long): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val query = "container_memory_usage_bytes{container_name!=\"\"} > 0"

        return queryPrometheus(query, company.monitoringId).mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val name = metric["container_name"]?.toString()
            if (!name.isNullOrBlank() && name != "metric-agent") {
                ContainerStatus(containerId = name, status = "RUNNING")
            } else null
        }.distinctBy { it.containerId }
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElse(null)?.monitoringId
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)

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

    fun getContainerMetrics(companyId: Long, containerName: String): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElse(null)?.monitoringId
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)

        return ResourceMetrics(
            status = "RUNNING",
            cpuUsage = querySingleValue("rate(container_cpu_usage_seconds_total{container_name=\"$containerName\"}[1m]) * 100", monId) ?: 0.0,
            memoryUsage = querySingleValue("container_memory_usage_bytes{container_name=\"$containerName\"}", monId) ?: 0.0,
            diskUsage = 0.0,
            networkTraffic = 0.0
        )
    }

    fun getLogs(companyId: Long, containerName: String?, severity: String?, keyword: String?, limit: Int): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val monId = company.monitoringId

        var logQuery = "{job=\"metric-agent\"}"
        if (!containerName.isNullOrBlank() && containerName != "all") logQuery += " |= \"$containerName\""
        if (!keyword.isNullOrBlank()) logQuery += " |= \"(?i)$keyword\""

        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/loki/api/v1/query_range")
            .queryParam("query", logQuery).queryParam("limit", limit).build().toUri()

        return try {
            val headers = HttpHeaders().apply { set("X-Server-Group", monId) }
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val result = (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>> ?: emptyList()

            val logs = mutableListOf<LogEntry>()
            for (stream in result) {
                val values = stream["values"] as? List<List<String>> ?: continue
                for (v in values) {
                    val raw = v[1]
                    var body = raw
                    var detectedSev = "INFO"

                    try {
                        val node = mapper.readTree(raw)
                        body = node.get("body")?.asText() ?: raw
                        val originalSev = node.get("severity")?.asText()?.uppercase() ?: "INFO"
                        detectedSev = when {
                            body.contains("error", true) || body.contains("fail", true) || body.contains("critical", true) -> "ERROR"
                            body.contains("warn", true) || body.contains("warning", true) || body.contains("slow", true) || body.contains("potential", true) -> "WARN"
                            else -> originalSev
                        }
                    } catch (e: Exception) {
                        detectedSev = if (raw.contains("error", true)) "ERROR" else if (raw.contains("warn", true)) "WARN" else "INFO"
                    }

                    logs.add(LogEntry(v[0], detectedSev, body, "Docker", "system", containerName, monId, raw))
                }
            }

            if (!severity.isNullOrBlank() && severity != "all") {
                logs.filter { it.severity == severity.uppercase() }.sortedByDescending { it.timestamp }
            } else {
                logs.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun queryPrometheus(query: String, monitoringId: String): List<Map<String, Any>> {
        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/api/v1/query")
            .queryParam("query", query).build().toUri()
        val headers = HttpHeaders().apply { set("X-Server-Group", monitoringId) }
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun querySingleValue(query: String, monitoringId: String): Double? =
        (queryPrometheus(query, monitoringId).firstOrNull()?.get("value") as? List<*>)
            ?.get(1)?.toString()?.toDoubleOrNull()
}
