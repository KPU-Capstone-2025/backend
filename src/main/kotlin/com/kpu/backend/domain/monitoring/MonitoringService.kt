package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.domain.alert.AlertRepository
import com.kpu.backend.domain.company.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class MonitoringService(
    private val companyRepository: CompanyRepository,
    private val alertRepository: AlertRepository,
    private val restTemplate: RestTemplate,
    @Value("\${aws.alb.dns.name}") private val albDnsName: String
) {
    private val log = LoggerFactory.getLogger(MonitoringService::class.java)
    private val mapper = ObjectMapper()

    fun getContainerList(companyId: Long): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val query = "container_memory_usage_bytes{container_name!=\"\"} > 0"

        return queryPrometheus(query, company.monitoringId).mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val name = metric["container_name"]?.toString()
            if (!name.isNullOrBlank() && name != "metric-agent") {
                ContainerStatus(containerId = name, status = "RUNNING")
            } else null
        }.distinctBy { it.containerId }
    }

    fun getHostMetrics(companyId: Long): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElse(null)?.monitoringId
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)

        val rx = querySingleValue("rate(system_network_rx_bytes[1m])", monId) ?: 0.0
        val tx = querySingleValue("rate(system_network_tx_bytes[1m])", monId) ?: 0.0

        return ResourceMetrics(
            status = "STABLE",
            cpuUsage = querySingleValue("system_cpu_usage", monId) ?: 0.0,
            memoryUsage = querySingleValue("system_memory_usage", monId) ?: 0.0,
            diskUsage = querySingleValue("system_disk_usage", monId) ?: 0.0,
            networkTraffic = rx + tx
        )
    }

    fun getContainerMetrics(companyId: Long, containerName: String): ResourceMetrics {
        val monId = companyRepository.findById(companyId).orElse(null)?.monitoringId
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)

        val memBytes = querySingleValue("container_memory_usage_bytes{container_name=\"$containerName\"}", monId) ?: 0.0
        val totalBytes = querySingleValue("system_memory_total_bytes", monId) ?: 0.0
        val memPct = if (totalBytes > 0) (memBytes / totalBytes) * 100.0 else 0.0

        return ResourceMetrics(
            status = "RUNNING",
            cpuUsage = querySingleValue("rate(container_cpu_usage_seconds_total{container_name=\"$containerName\"}[1m]) * 100", monId) ?: 0.0,
            memoryUsage = memPct,
            diskUsage = 0.0,
            networkTraffic = 0.0
        )
    }

    fun getLogs(companyId: Long, containerName: String?, severity: String?, keyword: String?, limit: Int): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val monId = company.monitoringId

        var logQuery = "{job=\"metric-agent\"}"
        if (!containerName.isNullOrBlank() && containerName != "all") logQuery += " |= \"$containerName\""
        if (!keyword.isNullOrBlank()) logQuery += " |= \"(?i)$keyword\""

        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/loki/api/v1/query_range")
            .queryParam("query", encodeQuery(logQuery)).queryParam("limit", limit).build(true).toUri()

        return try {
            val headers = HttpHeaders().apply { set("X-Server-Group", monId) }
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val result = (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>> ?: emptyList()

            val logs = mutableListOf<LogEntry>()
            for (stream in result) {
                val values = stream["values"] as? List<List<String>> ?: continue
                for (v in values) {
                    val raw = v[1]
                    var body = raw
                    var detectedSev = "INFO"

                    try {
                        val node = mapper.readTree(raw)
                        body = node.get("body")?.asText() ?: raw
                        val originalSev = node.get("severity")?.asText()?.uppercase() ?: "INFO"
                        detectedSev = when {
                            body.contains("error", true) || body.contains("fail", true) || body.contains("critical", true) -> "ERROR"
                            body.contains("warn", true) || body.contains("warning", true) || body.contains("slow", true) || body.contains("potential", true) -> "WARN"
                            else -> originalSev
                        }
                    } catch (e: Exception) {
                        detectedSev = if (raw.contains("error", true)) "ERROR" else if (raw.contains("warn", true)) "WARN" else "INFO"
                    }

                    logs.add(LogEntry(v[0], detectedSev, body, "Docker", "system", containerName, monId, raw))
                }
            }

            if (!severity.isNullOrBlank() && severity != "all") {
                logs.filter { it.severity == severity.uppercase() }.sortedByDescending { it.timestamp }
            } else {
                logs.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 월간 일별 집계
    // ──────────────────────────────────────────────────────────────

    /**
     * 월간(또는 임의 기간) 일별 리소스 집계를 반환합니다.
     *
     * @param companyId  company PK
     * @param year       조회 연도 (startDate 미지정 시 필수)
     * @param month      조회 월  (null이면 startDate/endDate 사용)
     * @param startDate  "yyyy-MM-dd" (month 미지정 시 사용)
     * @param endDate    "yyyy-MM-dd" (month 미지정 시 사용)
     */
    fun getMonthlyMetrics(
        companyId: Long,
        year: Int,
        month: Int?,
        startDate: String?,
        endDate: String?
    ): MonthlyMetricsResponse {
        val company = companyRepository.findById(companyId).orElse(null)
            ?: return MonthlyMetricsResponse(year, month, startDate, endDate, emptyList())
        val monId = company.monitoringId

        // ── 날짜 범위 계산 ──────────────────────────────────────────
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val (rangeStart, rangeEnd, allDates) = when {
            month != null -> {
                val ym = YearMonth.of(year, month)
                val s = ym.atDay(1)
                val e = ym.atEndOfMonth()
                Triple(s, e, (0 until ym.lengthOfMonth()).map { s.plusDays(it.toLong()) })
            }
            startDate != null && endDate != null -> {
                val s = LocalDate.parse(startDate, fmt)
                val e = LocalDate.parse(endDate, fmt)
                val days = generateSequence(s) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.toList()
                Triple(s, e, days)
            }
            else -> {
                val ym = YearMonth.of(year, 1)
                val s = ym.atDay(1)
                val e = ym.atEndOfMonth()
                Triple(s, e, (0 until ym.lengthOfMonth()).map { s.plusDays(it.toLong()) })
            }
        }

        val epochStart = rangeStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val epochEnd   = rangeEnd.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val step       = 3600  // 1시간 step → 하루 최대 24포인트

        // ── 호스트 메트릭 범위 쿼리 ──────────────────────────────────
        val hostCpuPts  = queryRange("system_cpu_usage",        monId, epochStart, epochEnd, step)
        val hostMemPts  = queryRange("system_memory_usage",     monId, epochStart, epochEnd, step)
        val hostDiskPts = queryRange("system_disk_usage",       monId, epochStart, epochEnd, step)
        val hostRxPts   = queryRange("rate(system_network_rx_bytes[1h])", monId, epochStart, epochEnd, step)
        val hostTxPts   = queryRange("rate(system_network_tx_bytes[1h])", monId, epochStart, epochEnd, step)
        val totalMemBytes = querySingleValue("system_memory_total_bytes", monId) ?: 0.0

        // ── 컨테이너 목록 조회 후 메트릭 범위 쿼리 ──────────────────
        val containers = getContainerList(companyId)
        // Map<containerId, Triple<cpuPts, memPts, netPts>>
        val containerSeries = containers.associate { c ->
            val id = c.containerId
            Triple(
                queryRange(
                    "rate(container_cpu_usage_seconds_total{container_name=\"$id\"}[1h]) * 100",
                    monId, epochStart, epochEnd, step
                ),
                queryRange(
                    "container_memory_usage_bytes{container_name=\"$id\"}",
                    monId, epochStart, epochEnd, step
                ),
                queryRange(
                    "rate(container_network_receive_bytes_total{container_name=\"$id\"}[1h])",
                    monId, epochStart, epochEnd, step
                )
            ).let { id to it }
        }

        // ── AlertLog: 기간 내 날짜별 카운트 ─────────────────────────
        val alertLogs = try {
            alertRepository.findByMonitoringIdAndCreatedAtBetween(
                monId,
                rangeStart.atStartOfDay(),
                rangeEnd.plusDays(1).atStartOfDay()
            )
        } catch (e: Exception) {
            emptyList()
        }
        val alertsByDate: Map<LocalDate, Int> = alertLogs
            .groupBy { it.createdAt.toLocalDate() }
            .mapValues { it.value.size }

        // ── 날짜별 집계 ─────────────────────────────────────────────
        val days = allDates.map { date ->
            // 호스트
            val cpuVals  = pointsForDay(hostCpuPts,  date)
            val memVals  = pointsForDay(hostMemPts,  date)  // already %
            val diskVals = pointsForDay(hostDiskPts, date)
            val rxVals   = pointsForDay(hostRxPts,   date)
            val txVals   = pointsForDay(hostTxPts,   date)
            // 네트워크: rx + tx pair-sum, 나머지는 독립 avg 합산
            val netVals  = mergeNetworkPts(rxVals, txVals).map { it / 1024.0 }  // bytes/s → KB/s

            val hasData = cpuVals.isNotEmpty()

            val hostMetrics = if (hasData) HostDailyMetrics(
                cpu     = statsOf(cpuVals),
                memory  = statsOf(memVals),
                disk    = statsOf(diskVals),
                network = statsOf(netVals)
            ) else null

            val worstStatus = computeWorstStatus(hasData, cpuVals, diskVals)

            // 컨테이너
            val containerMetrics = containerSeries.mapNotNull { (id, series) ->
                val (cpuS, memS, netS) = series
                val cCpu = pointsForDay(cpuS, date)
                val cMem = pointsForDay(memS, date).map { if (totalMemBytes > 0) (it / totalMemBytes) * 100.0 else 0.0 }
                val cNet = pointsForDay(netS, date).map { it / 1024.0 }
                if (cCpu.isEmpty() && cMem.isEmpty()) return@mapNotNull null
                ContainerDailyMetrics(
                    containerId = id,
                    status      = "RUNNING",
                    cpu         = statsOf(cCpu),
                    memory      = statsOf(cMem),
                    network     = statsOf(cNet)
                )
            }

            DailyMetrics(
                date        = date.toString(),
                hasData     = hasData,
                worstStatus = worstStatus,
                alertCount  = alertsByDate[date] ?: 0,
                host        = hostMetrics,
                containers  = containerMetrics
            )
        }

        return MonthlyMetricsResponse(year, month, startDate, endDate, days)
    }

    // ── Private helpers ──────────────────────────────────────────────

    /** Prometheus query_range 호출 → (epochSecond, value) 리스트 반환 */
    private fun queryRange(
        query: String,
        monitoringId: String,
        start: Long,
        end: Long,
        step: Int
    ): List<Pair<Long, Double>> {
        val uri = UriComponentsBuilder
            .fromUriString("http://$albDnsName/api/v1/query_range")
            .queryParam("query", encodeQuery(query))
            .queryParam("start", start)
            .queryParam("end",   end)
            .queryParam("step",  step)
            .build(true).toUri()

        return try {
            val headers = HttpHeaders().apply { set("X-Server-Group", monitoringId) }
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val results = (res.body?.get("data") as? Map<*, *>)
                ?.get("result") as? List<Map<*, *>> ?: return emptyList()

            // matrix 결과에서 첫 번째 시리즈만 사용 (단일 메트릭 쿼리 가정)
            val values = results.firstOrNull()?.get("values") as? List<*> ?: return emptyList()
            values.mapNotNull { v ->
                val pair = v as? List<*> ?: return@mapNotNull null
                val ts  = (pair[0] as? Number)?.toLong() ?: return@mapNotNull null
                val val_ = pair[1]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                ts to val_
            }
        } catch (e: Exception) {
            log.debug("queryRange failed: $query — ${e.message}")
            emptyList()
        }
    }

    /** 특정 날짜(UTC)에 속하는 포인트 값 목록 */
    private fun pointsForDay(pts: List<Pair<Long, Double>>, date: LocalDate): List<Double> =
        pts.filter { (ts, _) ->
            Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate() == date
        }.map { it.second }

    /** rx/tx 포인트를 합산 (같은 인덱스 쌍-합, 길이 다르면 짧은 쪽 기준) */
    private fun mergeNetworkPts(rx: List<Double>, tx: List<Double>): List<Double> {
        val size = minOf(rx.size, tx.size)
        return if (size == 0) {
            // 한쪽만 있으면 그대로 사용
            (rx + tx)
        } else {
            (0 until size).map { rx[it] + tx[it] }
        }
    }

    /** 값 목록 → MetricStats (빈 목록이면 모두 0.0) */
    private fun statsOf(vals: List<Double>): MetricStats {
        if (vals.isEmpty()) return MetricStats(0.0, 0.0, 0.0, 0.0)
        return MetricStats(
            avg    = vals.average().round2(),
            min    = vals.min().round2(),
            max    = vals.max().round2(),
            latest = vals.last().round2()
        )
    }

    private fun Double.round2() = Math.round(this * 100.0) / 100.0

    /** CPU/Disk 평균으로 worstStatus 결정 */
    private fun computeWorstStatus(hasData: Boolean, cpuVals: List<Double>, diskVals: List<Double>): String {
        if (!hasData) return "NO_DATA"
        val cpuAvg  = if (cpuVals.isNotEmpty())  cpuVals.average()  else 0.0
        val diskAvg = if (diskVals.isNotEmpty()) diskVals.average() else 0.0
        val cpuMax  = if (cpuVals.isNotEmpty())  cpuVals.max()      else 0.0
        return when {
            cpuMax > 90.0 || diskAvg > 90.0 -> "CRITICAL"
            cpuAvg > 70.0 || diskAvg > 80.0 -> "WARNING"
            else                             -> "STABLE"
        }
    }

    fun getAlertsByDate(companyId: Long, date: String): List<com.kpu.backend.domain.alert.AlertLog> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val dayStart = java.time.LocalDate.parse(date).atStartOfDay()
        return alertRepository.findByMonitoringIdAndCreatedAtBetween(company.monitoringId, dayStart, dayStart.plusDays(1))
    }

    fun getLogsByDateRange(companyId: Long, date: String): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val monId = company.monitoringId
        val dayStart = java.time.LocalDate.parse(date)
        val startNs = dayStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L
        val endNs   = dayStart.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L

        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/loki/api/v1/query_range")
            .queryParam("query", encodeQuery("{job=\"metric-agent\"}"))
            .queryParam("start", startNs)
            .queryParam("end",   endNs)
            .queryParam("limit", 200)
            .build(true).toUri()

        return try {
            val headers = HttpHeaders().apply { set("X-Server-Group", monId) }
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            val result = (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>> ?: emptyList()
            val logs = mutableListOf<LogEntry>()
            for (stream in result) {
                val values = stream["values"] as? List<List<String>> ?: continue
                for (v in values) {
                    val raw = v[1]; var body = raw; var sev = "INFO"
                    try {
                        val node = mapper.readTree(raw)
                        body = node.get("body")?.asText() ?: raw
                        sev = when {
                            body.contains("error", true) || body.contains("fail", true) || body.contains("critical", true) -> "ERROR"
                            body.contains("warn", true) -> "WARN"
                            else -> node.get("severity")?.asText()?.uppercase() ?: "INFO"
                        }
                    } catch (e: Exception) {
                        sev = if (raw.contains("error", true)) "ERROR" else if (raw.contains("warn", true)) "WARN" else "INFO"
                    }
                    logs.add(LogEntry(v[0], sev, body, "system", "host", null, monId, raw))
                }
            }
            logs.filter { it.severity == "ERROR" || it.severity == "WARN" }.sortedByDescending { it.timestamp }
        } catch (e: Exception) { emptyList() }
    }

    private fun encodeQuery(query: String): String =
        java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)

    private fun queryPrometheus(query: String, monitoringId: String): List<Map<String, Any>> {
        val uri = UriComponentsBuilder.fromUriString("http://$albDnsName/api/v1/query")
            .queryParam("query", encodeQuery(query)).build(true).toUri()
        val headers = HttpHeaders().apply { set("X-Server-Group", monitoringId) }
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(headers), Map::class.java)
            (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun querySingleValue(query: String, monitoringId: String): Double? =
        (queryPrometheus(query, monitoringId).firstOrNull()?.get("value") as? List<*>)
            ?.get(1)?.toString()?.toDoubleOrNull()
}
