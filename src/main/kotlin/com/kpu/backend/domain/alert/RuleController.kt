package com.kpu.backend.domain.alert

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rules")
class RuleController(private val alertRuleService: AlertRuleService) {

    @PostMapping("/update")
    fun update(@RequestBody request: RuleUpdateRequest): ResponseEntity<Unit> {
        alertRuleService.updateRules(request)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/{companyId}")
    fun getRules(
        @PathVariable companyId: Long,
        @RequestParam(required = false) hostName: String?
    ): ResponseEntity<AlertRuleSetting> {
        return ResponseEntity.ok(alertRuleService.getRuleSetting(companyId, hostName))
    }
}
