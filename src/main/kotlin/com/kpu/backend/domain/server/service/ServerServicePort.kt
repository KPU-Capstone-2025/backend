package com.kpu.backend.domain.server.service

import com.kpu.backend.domain.server.dto.ServerRegisterRequest
import com.kpu.backend.domain.server.dto.ServerResponse

interface ServerServicePort {

    fun getServers(companyId: Long): List<ServerResponse>

    fun registerServer(companyId: Long, req: ServerRegisterRequest): ServerResponse

    fun deleteServer(companyId: Long, serverId: Long)

    fun getDiscoveredHosts(companyId: Long, prometheusHosts: List<String>): List<Map<String, Any>>
}
