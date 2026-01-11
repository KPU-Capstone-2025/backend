package com.kpu.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BackendApplication

fun main(args: Array<String>) {
	runApplication<BackendApplication>(*args)
}
//curl -X POST "http://localhost:8080/api/ec2/create?userId=jonghee" - 맥북 터미널 테스트 명령어
//Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/ec2/create?userId=jonghee" - 윈도우 파워쉘 테스트 명령어