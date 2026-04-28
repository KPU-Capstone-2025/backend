package com.kpu.backend.domain.alert

import com.kpu.backend.domain.company.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.SendCommandRequest

@Service
class AlertRuleService(
    private val ssmClient: SsmClient,
    private val ec2Client: Ec2Client,
    private val companyRepository: CompanyRepository,
    @Value("\${spring.profiles.active:default}") private val activeProfile: String
) {
    private val log = LoggerFactory.getLogger(AlertRuleService::class.java)

    fun updateRules(request: RuleUpdateRequest) {
        val resolvedMonitoringId = resolveMonitoringId(request)

        if (activeProfile == "local") {
            log.info("[로컬 모드] 룰 업데이트 로직을 건너뜁니다. monitoringId=$resolvedMonitoringId")
            return
        }

        val instanceId = findMonitoringInstanceId(resolvedMonitoringId)
            ?: throw IllegalStateException("모니터링 서버를 찾을 수 없습니다. monitoringId=$resolvedMonitoringId")

        val hostFilter = if (!request.hostName.isNullOrBlank()) "{host_name=\"${request.hostName}\"}" else ""
        val suffix = if (!request.hostName.isNullOrBlank()) "_${request.hostName.replace("-", "_")}" else ""
        val serverDesc = if (!request.hostName.isNullOrBlank()) "[${request.hostName}] " else ""

        val yaml = """
            |groups:
            |  - name: rules$suffix
            |    rules:
            |      - alert: HighCpuUsage$suffix
            |        expr: system_cpu_usage$hostFilter > ${request.cpuThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: critical
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: ${serverDesc}CPU 과부하 감지
            |          description: ${serverDesc}서버의 CPU 사용량이 ${request.cpuThreshold}%를 초과했습니다.
            |
            |      - alert: HighMemoryUsage$suffix
            |        expr: system_memory_usage$hostFilter > ${request.memoryThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: critical
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: ${serverDesc}메모리 과부하 감지
            |          description: ${serverDesc}서버의 메모리 사용량이 ${request.memoryThreshold}%를 초과했습니다.
            |
            |      - alert: HighDiskUsage$suffix
            |        expr: system_disk_usage$hostFilter > ${request.diskThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: critical
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: ${serverDesc}디스크 용량 부족 감지
            |          description: ${serverDesc}서버의 디스크 사용량이 ${request.diskThreshold}%를 초과했습니다.
            |
            |      - alert: HighNetworkTraffic$suffix
            |        expr: rate(system_network_rx_bytes$hostFilter[1m]) + rate(system_network_tx_bytes$hostFilter[1m]) > ${request.networkThreshold}
            |        for: ${request.durationSeconds}s
            |        labels:
            |          severity: warning
            |          company_id: $resolvedMonitoringId
            |        annotations:
            |          summary: ${serverDesc}네트워크 트래픽 급증 감지
            |          description: ${serverDesc}서버의 네트워크 트래픽이 임계치(${request.networkThreshold} bytes/s)를 초과했습니다.
        """.trimMargin()

        val command = "cat << 'EOF' > /opt/monitoring/alert.rules.yml\n$yaml\nEOF\n(docker kill -s SIGHUP prometheus || docker restart prometheus)"

        val response = ssmClient.sendCommand(
            SendCommandRequest.builder()
                .documentName("AWS-RunShellScript")
                .instanceIds(instanceId)
                .parameters(mapOf("commands" to listOf(command)))
                .build()
        )

        log.info(
            "Rule update command sent. monitoringId={}, instanceId={}, commandId={}",
            resolvedMonitoringId, instanceId, response.command().commandId()
        )
    }

    private fun resolveMonitoringId(request: RuleUpdateRequest): String {
        if (!request.monitoringId.isNullOrBlank()) return request.monitoringId

        val companyIdOrMonitoringId = request.companyId?.trim()
            ?: throw IllegalArgumentException("companyId 또는 monitoringId 중 하나는 필수입니다.")

        if (companyIdOrMonitoringId.startsWith("mon-")) return companyIdOrMonitoringId

        val companyId = companyIdOrMonitoringId.toLongOrNull()
            ?: throw IllegalArgumentException("companyId 형식이 올바르지 않습니다: $companyIdOrMonitoringId")

        return companyRepository.findById(companyId)
            .orElseThrow { IllegalArgumentException("해당 companyId를 찾을 수 없습니다: $companyId") }
            .monitoringId
    }

    private fun findMonitoringInstanceId(monitoringId: String): String? {
        val filterMonitoringId = Filter.builder().name("tag:MonitoringId").values(monitoringId).build()
        val filterRole = Filter.builder().name("tag:Role").values("Monitoring").build()
        val filterRunning = Filter.builder().name("instance-state-name").values("running").build()

        val strictReq = DescribeInstancesRequest.builder()
            .filters(filterMonitoringId, filterRole, filterRunning)
            .build()

        val strictMatch = ec2Client.describeInstances(strictReq)
            .reservations()
            .flatMap { it.instances() }
            .firstOrNull()
            ?.instanceId()

        if (strictMatch != null) return strictMatch

        log.warn("No strict Role=Monitoring match for monitoringId={}. Falling back to legacy tag search.", monitoringId)

        val legacyReq = DescribeInstancesRequest.builder()
            .filters(filterMonitoringId, filterRunning)
            .build()

        return ec2Client.describeInstances(legacyReq)
            .reservations()
            .flatMap { it.instances() }
            .firstOrNull()
            ?.instanceId()
    }
}
