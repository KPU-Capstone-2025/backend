package com.kpu.backend.domain.alert.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.alert.dto.AlertRuleSettingResponse
import com.kpu.backend.domain.alert.dto.RuleUpdateRequest
import com.kpu.backend.domain.alert.service.AlertRuleServicePort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rules")
class RuleController(private val alertRuleService: AlertRuleServicePort) : AlertControllerDocs {

    @PostMapping("/update")
    override fun update(@RequestBody request: RuleUpdateRequest): ResponseEntity<ApiResponse<Nothing>> {
        alertRuleService.updateRules(request)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse(isSuccess = true, code = "201", message = "알림 규칙이 업데이트되었습니다."))
    }

    @GetMapping("/{companyId}")
    override fun getRules(
        @PathVariable companyId: Long,
        @RequestParam(required = false) hostName: String?
    ): ResponseEntity<ApiResponse<AlertRuleSettingResponse>> {
        val result = alertRuleService.getRuleSetting(companyId, hostName)
        return ResponseEntity.ok(
            ApiResponse(isSuccess = true, code = "200", message = "조회 성공", result = result)
        )
    }
}
