package com.kpu.backend.controller

import com.kpu.backend.dto.RuleUpdateRequest
import com.kpu.backend.service.AlertRuleService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rules")
class RuleController(
    private val alertRuleService: AlertRuleService
) {
    @PostMapping("/update")
    fun updateAlertRules(@RequestBody request: RuleUpdateRequest): ResponseEntity<String> {
        alertRuleService.updateRules(request)
        return ResponseEntity.ok("임계치가 변경되었습니다.")
    }
}