package com.kpu.backend.controller

import com.kpu.backend.dto.ProvisioningRequest
import com.kpu.backend.entity.Company
import com.kpu.backend.service.ProvisioningService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/provision")
class ProvisioningController(private val provisioningService: ProvisioningService) {

    @PostMapping("/signup")
    fun signup(@RequestBody request: ProvisioningRequest): Company {
        return provisioningService.provision(request)
    }
}