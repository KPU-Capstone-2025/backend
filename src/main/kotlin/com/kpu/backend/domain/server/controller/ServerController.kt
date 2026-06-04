package com.kpu.backend.domain.server.controller

import com.kpu.backend.config.ApiResponse
import com.kpu.backend.domain.server.dto.ServerRegisterRequest
import com.kpu.backend.domain.server.dto.ServerResponse
import com.kpu.backend.domain.server.service.ServerService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/servers")
class ServerController(private val serverService: ServerService) : ServerControllerDocs {

    @GetMapping("/{companyId}")
    override fun list(@PathVariable companyId: Long): ResponseEntity<ApiResponse<List<ServerResponse>>> {
        val result = serverService.getServers(companyId)
        return ResponseEntity.ok(ApiResponse(isSuccess = true, code = "200", message = "조회 성공", result = result))
    }

    @PostMapping("/{companyId}")
    override fun register(
        @PathVariable companyId: Long,
        @RequestBody req: ServerRegisterRequest
    ): ResponseEntity<ApiResponse<ServerResponse>> {
        val result = serverService.registerServer(companyId, req)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse(isSuccess = true, code = "201", message = "서버 등록 성공", result = result))
    }

    @DeleteMapping("/{companyId}/{serverId}")
    override fun delete(
        @PathVariable companyId: Long,
        @PathVariable serverId: Long
    ): ResponseEntity<ApiResponse<Nothing>> {
        serverService.deleteServer(companyId, serverId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
