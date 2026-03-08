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
        val query = "up{company_id='${company.monitoringId}'}"
        val results = queryPrometheus(query, company.monitoringId)
        
        return results.map {
            val metric = it["metric"] as Map<*, *>
            val value = (it["value"] as List<*>)[1].toString()
            ContainerStatus(
                containerId = metric["container_name"]?.toString() ?: "unknown",
                status = if (value == "1") "RUNNING" else "DOWN"
            )
        }
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val cpu = querySingleValue("system_cpu_usage{company_id='${company.monitoringId}'}", company.monitoringId)
        val mem = querySingleValue("system_memory_usage{company_id='${company.monitoringId}'}", company.monitoringId)
        return ResourceMetrics("STABLE", cpu ?: 0.0, mem ?: 0.0, 0.0, 0.0)
    }

    fun getContainerMetrics(companyId: Long, containerId: String, period: String): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElseThrow()
        val cpu = querySingleValue("avg_over_time(container_cpu_usage{company_id='${company.monitoringId}', container_name='$containerId'}[$period])", company.monitoringId)
        val mem = querySingleValue("avg_over_time(container_memory_usage{company_id='${company.monitoringId}', container_name='$containerId'}[$period])", company.monitoringId)
        return ResourceMetrics("RUNNING", cpu ?: 0.0, mem ?: 0.0, 0.0, 0.0)
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
        } catch (e: Exception) { emptyList() }
    }
}