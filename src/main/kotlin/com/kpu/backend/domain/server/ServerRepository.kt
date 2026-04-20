package com.kpu.backend.domain.server

import org.springframework.data.jpa.repository.JpaRepository

interface ServerRepository : JpaRepository<Server, Long> {
    fun findAllByCompanyId(companyId: Long): List<Server>
    fun findByCompanyIdAndName(companyId: Long, name: String): Server?
}
