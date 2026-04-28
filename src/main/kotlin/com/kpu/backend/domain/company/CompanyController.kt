package com.kpu.backend.domain.company

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/company")
@CrossOrigin("*")
class CompanyController(private val companyService: CompanyService) {

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val response = companyService.login(req) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(response)
    }

    @PostMapping("/register")
    fun register(@RequestBody req: CompanyRegisterRequest): ResponseEntity<Map<String, String>> {
        companyService.registerAndProvision(req)
        return ResponseEntity.ok(mapOf("status" to "success"))
    }

    @GetMapping("/agent/{companyId}")
    fun getAgent(@PathVariable companyId: Long) =
        ResponseEntity.ok(companyService.getAgentInfo(companyId))
}
