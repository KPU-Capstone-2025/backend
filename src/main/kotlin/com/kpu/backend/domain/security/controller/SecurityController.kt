package com.kpu.backend.domain.security.controller

import com.kpu.backend.domain.security.dto.SshStatsResponse
import com.kpu.backend.domain.security.service.SecurityService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/security")
class SecurityController(private val securityService: SecurityService) {

    @GetMapping("/ssh-stats/{companyId}")
    fun getSshStats(
        @PathVariable companyId: Long,
        @RequestParam(defaultValue = "33") days: Int
    ): ResponseEntity<SshStatsResponse> =
        ResponseEntity.ok(securityService.getSshStats(companyId, days))
}
