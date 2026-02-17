package com.kpu.backend.controller

import com.kpu.backend.dto.AuthResponse
import com.kpu.backend.dto.LoginRequest
import com.kpu.backend.dto.SignUpRequest
import com.kpu.backend.service.AuthService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/signup")
    fun signUp(@RequestBody request: SignUpRequest): AuthResponse {
        val result = authService.signUp(request)
        return AuthResponse(
            success = true,
            message = result
        )
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): AuthResponse {
        val result = authService.login(request)
        return AuthResponse(
            success = true,
            message = result
        )
    }
}