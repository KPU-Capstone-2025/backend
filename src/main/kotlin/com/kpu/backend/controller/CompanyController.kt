package com.kpu.backend.controller

import com.kpu.backend.dto.CompanyRegisterRequest
import com.kpu.backend.dto.LoginRequest
import com.kpu.backend.dto.LoginResponse
import com.kpu.backend.dto.AgentDestination
import com.kpu.backend.common.ApiResponse
import com.kpu.backend.service.CompanyService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 회원가입 경로: POST /api/company/register
 */
@RestController
@RequestMapping("/api")
@CrossOrigin("*")
class CompanyController(private val companyService: CompanyService) {

    @PostMapping("/company/register")
    fun register(@RequestBody req: CompanyRegisterRequest): ResponseEntity<Map<String, String>> {
        companyService.registerAndProvision(req)
        return ResponseEntity.ok(mapOf("status" to "success", "message" to "회원가입 및 서버 생성이 완료되었습니다."))
    }

    @PostMapping("/company/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val id = companyService.login(req) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(LoginResponse(id))
    }

    @GetMapping("/agent/{companyId}")
    fun getAgent(@PathVariable companyId: Long): ApiResponse<AgentDestination> {
        return try {
            ApiResponse(true, "company200-1", "성공", companyService.getAgentInfo(companyId))
        } catch (e: Exception) {
            ApiResponse(false, "company403-1", e.message ?: "Error", null)
        }
    }
}