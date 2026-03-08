package com.kpu.backend.repository

import com.kpu.backend.entity.Company
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CompanyRepository : JpaRepository<Company, Long> {
    fun findByEmail(email: String): Company?
}