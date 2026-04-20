package com.kpu.backend.domain.server

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/servers")
@CrossOrigin("*")
class ServerController(private val serverService: ServerService) {

    @GetMapping("/{companyId}")
    fun list(@PathVariable companyId: Long) = ResponseEntity.ok(serverService.getServers(companyId))

    @PostMapping("/{companyId}")
    fun register(@PathVariable companyId: Long, @RequestBody req: ServerRegisterRequest) =
        ResponseEntity.ok(serverService.registerServer(companyId, req))

    @DeleteMapping("/{companyId}/{serverId}")
    fun delete(@PathVariable companyId: Long, @PathVariable serverId: Long): ResponseEntity<Void> {
        serverService.deleteServer(companyId, serverId)
        return ResponseEntity.noContent().build()
    }
}
