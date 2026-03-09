package com.kpu.backend.controller

import com.kpu.backend.common.ApiResponse
import com.kpu.backend.dto.ContainerStatus
import com.kpu.backend.dto.ResourceMetrics
import com.kpu.backend.service.MonitoringService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin("*")
class DashboardController(private val monitoringService: MonitoringService) {

    /**
     * 업체별 컨테이너 목록 조회
     * GET /api/dashboard/container/{companyId}
     */
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

    /**
     * 호스트 서버 리소스 조회
     * GET /api/dashboard/{companyId}/host
     */
    @GetMapping("/{companyId}/host")
    fun host(@PathVariable companyId: Long): ApiResponse<ResourceMetrics> {
        return ApiResponse(
            isSuccess = true,
            code = "dashboard200-1",
            message = "성공적으로 호스트 리소스 정보를 가져왔습니다.",
            result = monitoringService.getHostMetrics(companyId)
        )
    }

    /**
     * 컨테이너별 상세 리소스 조회
     * GET /api/dashboard/{companyId}/{containerId}
     */
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

}