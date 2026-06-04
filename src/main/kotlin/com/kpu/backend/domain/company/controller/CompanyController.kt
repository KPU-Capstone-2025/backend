package com.kpu.backend.domain.company.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.company.dto.AgentDestination
import com.kpu.backend.domain.company.dto.CompanyRegisterRequest
import com.kpu.backend.domain.company.dto.LoginRequest
import com.kpu.backend.domain.company.dto.LoginResponse
import com.kpu.backend.domain.company.service.CompanyServicePort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/company")
class CompanyController(private val companyService: CompanyServicePort) : CompanyControllerDocs {

    @PostMapping("/login")
    override fun login(@RequestBody req: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        val response = companyService.login(req)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(isSuccess = false, code = "401", message = "이메일 또는 비밀번호가 올바르지 않습니다."))
        return ResponseEntity.ok(ApiResponse(isSuccess = true, code = "200", message = "로그인 성공", result = response))
    }

    @PostMapping("/register")
    override fun register(@RequestBody req: CompanyRegisterRequest): ResponseEntity<ApiResponse<Nothing>> {
        companyService.registerAndProvision(req)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(isSuccess = true, code = "201", message = "회사 등록 및 인프라 프로비저닝이 시작되었습니다."))
    }

    @GetMapping("/agent/{companyId}")
    override fun getAgent(@PathVariable companyId: Long): ResponseEntity<ApiResponse<AgentDestination>> {
        val result = companyService.getAgentInfo(companyId)
        return ResponseEntity.ok(ApiResponse(isSuccess = true, code = "200", message = "조회 성공", result = result))
    }
}
