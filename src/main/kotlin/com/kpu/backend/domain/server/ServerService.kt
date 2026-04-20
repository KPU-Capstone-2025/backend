package com.kpu.backend.domain.server

import com.kpu.backend.domain.company.CompanyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ServerService(
    private val serverRepository: ServerRepository,
    private val companyRepository: CompanyRepository
) {
    fun getServers(companyId: Long): List<ServerResponse> {
        val company = companyRepository.findById(companyId).orElseThrow()
        return serverRepository.findAllByCompanyId(companyId).map {
            ServerResponse(it.id!!, it.name, it.description, it.createdAt, company.collectorUrl, company.monitoringId)
        }
    }

    @Transactional
    fun registerServer(companyId: Long, req: ServerRegisterRequest): ServerResponse {
        val company = companyRepository.findById(companyId).orElseThrow()
        val server = serverRepository.save(Server(companyId = companyId, name = req.name, description = req.description))
        return ServerResponse(server.id!!, server.name, server.description, server.createdAt, company.collectorUrl, company.monitoringId)
    }

    @Transactional
    fun deleteServer(companyId: Long, serverId: Long) {
        val server = serverRepository.findById(serverId).orElseThrow()
        require(server.companyId == companyId) { "권한 없음" }
        serverRepository.delete(server)
    }

    fun getDiscoveredHosts(companyId: Long, prometheusHosts: List<String>): List<Map<String, Any>> {
        val registered = serverRepository.findAllByCompanyId(companyId).map { it.name }.toSet()
        return prometheusHosts.map { host ->
            mapOf("hostName" to host, "registered" to registered.contains(host))
        }
    }
}
