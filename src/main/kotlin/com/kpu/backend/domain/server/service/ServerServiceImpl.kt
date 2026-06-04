package com.kpu.backend.domain.server.service

import com.kpu.backend.config.exception.BusinessException
import com.kpu.backend.config.exception.ErrorCode
import com.kpu.backend.domain.company.repository.CompanyRepository
import com.kpu.backend.domain.server.dto.ServerRegisterRequest
import com.kpu.backend.domain.server.dto.ServerResponse
import com.kpu.backend.domain.server.entity.Server
import com.kpu.backend.domain.server.repository.ServerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ServerServiceImpl(
    private val serverRepository: ServerRepository,
    private val companyRepository: CompanyRepository
) : ServerServicePort {

    override fun getServers(companyId: Long): List<ServerResponse> {
        val company = companyRepository.findById(companyId)
            .orElseThrow { BusinessException(ErrorCode.COMPANY_NOT_FOUND, "회사를 찾을 수 없습니다: $companyId") }
        return serverRepository.findAllByCompanyId(companyId).map {
            ServerResponse(it.id!!, it.name, it.description, it.createdAt, company.collectorUrl, company.monitoringId)
        }
    }

    @Transactional
    override fun registerServer(companyId: Long, req: ServerRegisterRequest): ServerResponse {
        val company = companyRepository.findById(companyId)
            .orElseThrow { BusinessException(ErrorCode.COMPANY_NOT_FOUND, "회사를 찾을 수 없습니다: $companyId") }
        val server = serverRepository.save(Server(companyId = companyId, name = req.name, description = req.description))
        return ServerResponse(server.id!!, server.name, server.description, server.createdAt, company.collectorUrl, company.monitoringId)
    }

    @Transactional
    override fun deleteServer(companyId: Long, serverId: Long) {
        val server = serverRepository.findById(serverId)
            .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT, "서버를 찾을 수 없습니다: $serverId") }
        require(server.companyId == companyId) { "해당 서버에 대한 권한이 없습니다." }
        serverRepository.delete(server)
    }

    override fun getDiscoveredHosts(companyId: Long, prometheusHosts: List<String>): List<Map<String, Any>> {
        val registered = serverRepository.findAllByCompanyId(companyId).map { it.name }.toSet()
        return prometheusHosts.map { host ->
            mapOf("hostName" to host, "registered" to registered.contains(host))
        }
    }
}
