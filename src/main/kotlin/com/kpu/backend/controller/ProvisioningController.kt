package com.kpu.monitor.controller

import com.kpu.monitor.dto.ProvisioningRequest
import com.kpu.monitor.entity.Company
import com.kpu.monitor.service.ProvisioningService
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