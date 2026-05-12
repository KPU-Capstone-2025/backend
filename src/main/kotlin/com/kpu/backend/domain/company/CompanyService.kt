package com.kpu.backend.domain.company

import com.kpu.backend.config.JwtUtil
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val jwtUtil: JwtUtil
) {
    @Transactional
    fun registerAndProvision(req: CompanyRegisterRequest): Company {
        val monitoringId = "mon-" + UUID.randomUUID().toString().take(8)
        val nextId = companyRepository.findMaxId() + 1
        return companyRepository.save(
            Company(
                id = nextId,
                name = req.name,
                email = req.email,
                password = passwordEncoder.encode(req.password),
                phone = req.phone,
                monitoringId = monitoringId,
                collectorUrl = req.ip,
                ip = req.ip
            )
        )
    }

    fun login(req: LoginRequest): LoginResponse? {
        val company = companyRepository.findByEmail(req.email) ?: return null
        if (!passwordEncoder.matches(req.password, company.password)) return null
        val token = jwtUtil.generateToken(company.id, company.monitoringId)
        return LoginResponse(company.id, company.name, company.monitoringId, token)
    }

    fun getAgentInfo(companyId: Long): AgentDestination {
        val company = companyRepository.findById(companyId).orElseThrow()
        return AgentDestination(company.monitoringId, "${company.collectorUrl}:4318")
    }
}
