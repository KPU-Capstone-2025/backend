package com.kpu.backend.controller


import com.kpu.backend.service.Ec2Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestMapping

@RestController
@RequestMapping("/api/ec2")
class Ec2Controller(private val ec2Service: Ec2Service) {

    @PostMapping("/create")
    fun createInstance(@RequestParam userId: String): String {
        return ec2Service.createMyEc2(userId)
    }
}