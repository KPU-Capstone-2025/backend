package com.kpu.backend.domain.company.service

import com.kpu.backend.domain.company.dto.AgentDestination
import com.kpu.backend.domain.company.dto.CompanyRegisterRequest
import com.kpu.backend.domain.company.dto.LoginRequest
import com.kpu.backend.domain.company.dto.LoginResponse

interface CompanyService {
    fun registerAndProvision(req: CompanyRegisterRequest)
    fun login(req: LoginRequest): LoginResponse?
    fun getAgentInfo(companyId: Long): AgentDestination
}
