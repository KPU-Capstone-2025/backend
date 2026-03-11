package com.kpu.backend.controller

import com.kpu.backend.common.ApiResponse
import com.kpu.backend.dto.ContainerStatus
import com.kpu.backend.dto.LogEntry
import com.kpu.backend.dto.ResourceMetrics
import com.kpu.backend.service.MonitoringService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin("*")
class DashboardController(private val monitoringService: MonitoringService) {

    @GetMapping("/container/{companyId}")
    fun list(
        @PathVariable companyId: Long,
        @RequestParam(defaultValue = "120") freshWindowSec: Int
    ): ApiResponse<ContainerStatus> {
        return ApiResponse(
            isSuccess = true,
            code = "dashboard200-1",
            message = "성공적으로 컨테이너 목록을 조회했습니다.",
            containers = monitoringService.getContainerList(companyId, freshWindowSec)
        )
    }

    @GetMapping("/{companyId}/host")
    fun host(@PathVariable companyId: Long): ApiResponse<ResourceMetrics> {
        return ApiResponse(
            isSuccess = true,
            code = "dashboard200-1",
            message = "성공적으로 호스트 리소스 정보를 가져왔습니다.",
            result = monitoringService.getHostMetrics(companyId)
        )
    }

    @GetMapping("/{companyId}/{containerId}")
    fun detail(
        @PathVariable companyId: Long,
        @PathVariable containerId: String,
        @RequestParam(defaultValue = "1h") period: String
    ): ApiResponse<ResourceMetrics> {
        return ApiResponse(
            isSuccess = true,
            code = "dashboard200-1",
            message = "성공적으로 컨테이너 리소스를 상세 조회했습니다.",
            results = monitoringService.getContainerMetrics(companyId, containerId, period)
        )
    }

    @GetMapping("/{companyId}/logs")
    fun logs(
        @PathVariable companyId: Long,
        @RequestParam(required = false) containerId: String?,
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) startTime: Long?,
        @RequestParam(required = false) endTime: Long?,
        @RequestParam(defaultValue = "100") limit: Int
    ): ApiResponse<LogEntry> {
        return ApiResponse(
            isSuccess = true,
            code = "dashboard200-1",
            message = "성공적으로 로그를 조회했습니다.",
            containers = monitoringService.getLogs(companyId, containerId, level, keyword, startTime, endTime, limit)
        )
    }
}