package com.kpu.backend.domain.security.service

import com.kpu.backend.domain.security.dto.SshStatsResponse

interface SecurityService {
    fun getSshStats(companyId: Long, days: Int): SshStatsResponse
}
