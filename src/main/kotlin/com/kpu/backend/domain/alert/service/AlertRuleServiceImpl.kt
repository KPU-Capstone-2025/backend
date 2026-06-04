package com.kpu.backend.domain.alert.service

import com.kpu.backend.config.exception.BusinessException
import com.kpu.backend.config.exception.ErrorCode
import com.kpu.backend.domain.alert.dto.AlertRuleSettingResponse
import com.kpu.backend.domain.alert.dto.RuleUpdateRequest
import com.kpu.backend.domain.alert.entity.AlertRuleSetting
import com.kpu.backend.domain.alert.repository.AlertRuleSettingRepository
import com.kpu.backend.domain.company.repository.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.SendCommandRequest

@Service
class AlertRuleServiceImpl(
    private val ssmClient: SsmClient,
    private val ec2Client: Ec2Client,
    private val companyRepository: CompanyRepository,
    private val alertRuleSettingRepository: AlertRuleSettingRepository,
    @Value("\${spring.profiles.active:default}") private val activeProfile: String
) : AlertRuleServicePort {

    private val log = LoggerFactory.getLogger(AlertRuleServiceImpl::class.java)

    override fun getRuleSetting(companyId: Long, hostName: String?): AlertRuleSettingResponse {
        val entity = alertRuleSettingRepository.findByCompanyIdAndHostName(companyId, hostName)
            ?: AlertRuleSetting(companyId = companyId, hostName = hostName)
        return AlertRuleSettingResponse.from(entity)
    }

    override fun updateRules(request: RuleUpdateRequest) {
        val resolvedMonitoringId = resolveMonitoringId(request)

        if (activeProfile == "local") {
            log.info("[로컬 모드] 룰 업데이트 로직을 건너뜁니다. monitoringId={}", resolvedMonitoringId)
            persistRuleSetting(resolvedMonitoringId, request)
            return
        }

        val instanceId = findMonitoringInstanceId(resolvedMonitoringId)
            ?: throw BusinessException(
                ErrorCode.MONITORING_SERVER_NOT_FOUND,
                "모니터링 서버를 찾을 수 없습니다. monitoringId=$resolvedMonitoringId"
            )

        val hostFilter = if (!request.hostName.isNullOrBlank()) "{host_name=\"${request.hostName}\"}" else ""
        val suffix    = if (!request.hostName.isNullOrBlank()) "_${request.hostName!!.replace("-", "_")}" else ""
        val serverDesc = if (!request.hostName.isNullOrBlank()) "${request.hostName} " else ""

        val yaml = buildAlertRulesYaml(request, resolvedMonitoringId, hostFilter, suffix, serverDesc)

        val ruleFile = if (!request.hostName.isNullOrBlank())
            "/opt/monitoring/alert.rules${suffix}.yml"
        else
            "/opt/monitoring/alert.rules.yml"

        val command = "cat << 'EOF' > $ruleFile\n$yaml\nEOF\n" +
            "(docker kill -s SIGHUP monitoring-prometheus-1 || docker restart monitoring-prometheus-1)"

        try {
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
        } catch (e: Exception) {
            log.error("SSM 명령 전송 실패. monitoringId={}", resolvedMonitoringId, e)
            throw BusinessException(ErrorCode.RULE_DEPLOY_FAILED, "알림 규칙 배포 중 오류가 발생했습니다: ${e.message}")
        }

        persistRuleSetting(resolvedMonitoringId, request)
    }

    private fun persistRuleSetting(monitoringId: String, request: RuleUpdateRequest) {
        val companyId = companyRepository.findByMonitoringId(monitoringId)?.id ?: return
        val setting = alertRuleSettingRepository.findByCompanyIdAndHostName(companyId, request.hostName)
            ?: AlertRuleSetting(companyId = companyId, hostName = request.hostName)
        setting.cpuThreshold        = request.cpuThreshold
        setting.memoryThreshold     = request.memoryThreshold
        setting.diskThreshold       = request.diskThreshold
        setting.diskIoThreshold     = request.diskIoThreshold
        setting.userCountThreshold  = request.userCountThreshold
        setting.networkInThreshold  = request.networkInThreshold
        setting.networkOutThreshold = request.networkOutThreshold
        setting.durationSeconds     = request.durationSeconds
        alertRuleSettingRepository.save(setting)
    }

    private fun resolveMonitoringId(request: RuleUpdateRequest): String {
        if (!request.monitoringId.isNullOrBlank()) return request.monitoringId

        val raw = request.companyId?.trim()
            ?: throw BusinessException(ErrorCode.MISSING_REQUIRED_FIELD, "companyId 또는 monitoringId 중 하나는 필수입니다.")

        if (raw.startsWith("mon-")) return raw

        val companyId = raw.toLongOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "companyId 형식이 올바르지 않습니다: $raw")

        return companyRepository.findById(companyId)
            .orElseThrow { BusinessException(ErrorCode.COMPANY_NOT_FOUND, "해당 companyId를 찾을 수 없습니다: $companyId") }
            .monitoringId
    }

    private fun findMonitoringInstanceId(monitoringId: String): String? {
        val filterMonitoringId = Filter.builder().name("tag:MonitoringId").values(monitoringId).build()
        val filterRole         = Filter.builder().name("tag:Role").values("Monitoring").build()
        val filterRunning      = Filter.builder().name("instance-state-name").values("running").build()

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

    private fun buildAlertRulesYaml(
        request: RuleUpdateRequest,
        monitoringId: String,
        hostFilter: String,
        suffix: String,
        serverDesc: String
    ): String = """
        |groups:
        |  - name: rules$suffix
        |    rules:
        |      - alert: HighCpuUsage$suffix
        |        expr: system_cpu_usage$hostFilter > ${request.cpuThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: critical
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}CPU 과부하 감지
        |          description: ${serverDesc}서버의 CPU 사용량이 ${request.cpuThreshold}%를 초과했습니다.
        |
        |      - alert: HighMemoryUsage$suffix
        |        expr: system_memory_usage$hostFilter > ${request.memoryThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: critical
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}메모리 과부하 감지
        |          description: ${serverDesc}서버의 메모리 사용량이 ${request.memoryThreshold}%를 초과했습니다.
        |
        |      - alert: HighDiskUsage$suffix
        |        expr: system_disk_usage$hostFilter > ${request.diskThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: critical
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}디스크 용량 부족 감지
        |          description: ${serverDesc}서버의 디스크 사용량이 ${request.diskThreshold}%를 초과했습니다.
        |
        |      - alert: HighDiskIO$suffix
        |        expr: rate(system_disk_read_bytes$hostFilter[1m]) + rate(system_disk_write_bytes$hostFilter[1m]) > ${request.diskIoThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: warning
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}디스크 I/O 급증 감지
        |          description: ${serverDesc}서버의 디스크 I/O가 임계치(${request.diskIoThreshold} bytes/s)를 초과했습니다.
        |
        |      - alert: HighUserCount$suffix
        |        expr: last_over_time(system_logged_in_users$hostFilter[1m]) > ${request.userCountThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: warning
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}동시 접속자 수 초과
        |          description: ${serverDesc}서버에 로그인한 사용자 수가 ${request.userCountThreshold}명을 초과했습니다.
        |
        |      - alert: HighNetworkIn$suffix
        |        expr: rate(system_network_rx_bytes$hostFilter[1m]) > ${request.networkInThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: warning
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}네트워크 수신 트래픽 급증
        |          description: ${serverDesc}서버의 네트워크 수신량이 임계치(${request.networkInThreshold} bytes/s)를 초과했습니다.
        |
        |      - alert: HighNetworkOut$suffix
        |        expr: rate(system_network_tx_bytes$hostFilter[1m]) > ${request.networkOutThreshold}
        |        for: ${request.durationSeconds}s
        |        labels:
        |          severity: warning
        |          company_id: $monitoringId
        |        annotations:
        |          summary: ${serverDesc}네트워크 송신 트래픽 급증
        |          description: ${serverDesc}서버의 네트워크 송신량이 임계치(${request.networkOutThreshold} bytes/s)를 초과했습니다.
    """.trimMargin()
}
