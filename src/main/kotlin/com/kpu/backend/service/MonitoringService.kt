package com.kpu.backend.service

import com.kpu.backend.dto.ContainerStatus
import com.kpu.backend.dto.LogEntry
import com.kpu.backend.dto.ResourceMetrics
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
        companyId: Long, 
        containerId: String?, 
        level: String?, 
        keyword: String?, 
        startTimeMs: Long?, 
        endTimeMs: Long?, 
        limit: Int
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
                val labels = (streamMap["stream"] as? Map<*, *>)
                    ?.mapKeys { it.key.toString() }
                    ?.mapValues { it.value?.toString() ?: "" }
                    ?: emptyMap()

                val values = streamMap["values"] as? List<*> ?: return@flatMap emptyList()
                values.mapNotNull { valueObj ->
                    val pair = valueObj as? List<*> ?: return@mapNotNull null
                    if (pair.size < 2) return@mapNotNull null

                    LogEntry(
                        timestamp = pair[0]?.toString() ?: "",
                        message = pair[1]?.toString() ?: "",
                        labels = labels
                    )
                }
            }
        } catch (e: Exception) {
            // 에러 발생 시 빈 리스트 반환 (Loki 쿼리 에러 방어)
            emptyList() 
        }
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