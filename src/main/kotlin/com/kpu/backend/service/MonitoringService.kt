package com.kpu.backend.service

import com.kpu.backend.dto.ContainerStatus
import com.kpu.backend.dto.ResourceMetrics
import com.kpu.backend.repository.CompanyRepository
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
    private val promUrl = "http://$albDnsName/api/v1/query"

    fun getContainerList(companyId: Long, freshWindowSec: Int): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElseThrow()

        val safeWindow = freshWindowSec.coerceIn(10, 3600)
        // 최근 윈도우 내 샘플이 존재하는 컨테이너만 목록에 포함한다.
        val query = "count_over_time(container_memory_usage_bytes[${safeWindow}s])"
        val results = queryPrometheus(query, company.monitoringId)

        return results.mapNotNull {
            val metric = it["metric"] as? Map<*, *> ?: return@mapNotNull null
            val containerName = metric["container_name"]?.toString()
                ?: metric["container"]?.toString()
                ?: metric["container_id"]?.toString()
            val sampleCount = (it["value"] as? List<*>)?.getOrNull(1)?.toString()?.toDoubleOrNull() ?: 0.0

            if (!containerName.isNullOrBlank() && sampleCount > 0.0) {
                ContainerStatus(containerId = containerName, status = "RUNNING")
            } else null
        }.distinctBy { it.containerId } 
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val monId = company.monitoringId

        val cpu = querySingleValueAny(
            listOf(
                "system_cpu_usage",
                "system_cpu_usage_percent",
                "system_cpu_usage_ratio * 100"
            ),
            monId
        ) ?: 0.0
        val mem = querySingleValueAny(
            listOf(
                "system_memory_usage",
                "system_memory_usage_percent"
            ),
            monId
        ) ?: 0.0
        val disk = querySingleValueAny(
            listOf(
                "system_disk_usage",
                "system_disk_usage_percent"
            ),
            monId
        ) ?: 0.0
        val rx = querySingleValueAny(
            listOf(
                "system_network_rx_bytes",
                "rate(system_network_rx_bytes[1m])"
            ),
            monId
        ) ?: 0.0
        val tx = querySingleValueAny(
            listOf(
                "system_network_tx_bytes",
                "rate(system_network_tx_bytes[1m])"
            ),
            monId
        ) ?: 0.0
        
        return ResourceMetrics("STABLE", cpu, mem, disk, rx + tx)
    }

    fun getContainerMetrics(companyId: Long, containerId: String, period: String): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val monId = company.monitoringId
        val escapedContainerId = containerId.replace("'", "\\\\'")
        
        val cpuQuery = "rate(container_cpu_usage_ns{container_name='$escapedContainerId'}[1m]) / 10000000 or rate(container_cpu_usage_ns{container='$escapedContainerId'}[1m]) / 10000000"
        val memQuery = "container_memory_usage_bytes{container_name='$escapedContainerId'} or container_memory_usage_bytes{container='$escapedContainerId'}"
        val rxQuery = "rate(container_network_rx_bytes{container_name='$escapedContainerId'}[1m]) or rate(container_network_rx_bytes{container='$escapedContainerId'}[1m])"
        val txQuery = "rate(container_network_tx_bytes{container_name='$escapedContainerId'}[1m]) or rate(container_network_tx_bytes{container='$escapedContainerId'}[1m])"
        
        val cpu = querySingleValue(cpuQuery, monId) ?: 0.0
        val mem = querySingleValue(memQuery, monId) ?: 0.0
        val rx = querySingleValue(rxQuery, monId) ?: 0.0
        val tx = querySingleValue(txQuery, monId) ?: 0.0
        
        return ResourceMetrics("RUNNING", cpu, mem, 0.0, rx + tx)
    }

    private fun querySingleValueAny(queries: List<String>, monitoringId: String): Double? {
        for (query in queries) {
            val value = querySingleValue(query, monitoringId)
            if (value != null) return value
        }
        return null
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