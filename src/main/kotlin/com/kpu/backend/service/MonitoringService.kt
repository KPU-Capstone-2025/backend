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
    private val promUrl = "http://$albDnsName:4318/api/v1/query"

    fun getContainerList(companyId: Long): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElseThrow()
        
        val query = "container_memory_usage_bytes"
        val results = queryPrometheus(query, company.monitoringId)

        return results.mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val containerName = metric["container_name"]?.toString()
            
            if (!containerName.isNullOrBlank()) {
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
        val rx = querySingleValue("system_network_rx_bytes", monId) ?: 0.0
        val tx = querySingleValue("system_network_tx_bytes", monId) ?: 0.0
        
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