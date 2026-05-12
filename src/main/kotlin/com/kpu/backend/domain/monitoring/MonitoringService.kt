package com.kpu.backend.domain.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.kpu.backend.domain.alert.AlertRepository
import com.kpu.backend.domain.company.CompanyRepository
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class MonitoringService(
    private val companyRepository: CompanyRepository,
    private val alertRepository: AlertRepository,
    private val restTemplate: RestTemplate
) {
    private val log = LoggerFactory.getLogger(MonitoringService::class.java)
    private val mapper = ObjectMapper()

    private fun prometheusUrl(ip: String) = "http://$ip:9090"
    private fun lokiUrl(ip: String) = "http://$ip:3100"

    fun getContainerList(companyId: Long, hostName: String? = null): List<ContainerStatus> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val ip = company.ip ?: return emptyList()
        val query = if (hostName != null)
            "container_memory_usage_bytes{container_name!=\"\",host_name=\"$hostName\"} > 0"
        else
            "container_memory_usage_bytes{container_name!=\"\"} > 0"

        return queryPrometheus(query, prometheusUrl(ip)).mapNotNull {
            val metric = it["metric"] as Map<*, *>
            val name = metric["container_name"]?.toString()
            if (!name.isNullOrBlank() && name != "metric-agent") {
                ContainerStatus(containerId = name, status = "RUNNING")
            } else null
        }.distinctBy { it.containerId }
    }

    fun getHostMetrics(companyId: Long, hostName: String? = null): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElse(null)
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)
        val ip = company.ip
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)
        val promUrl = prometheusUrl(ip)

        fun q(metric: String) = if (hostName != null) "$metric{host_name=\"$hostName\"}" else metric
        val hf = if (hostName != null) "{host_name=\"$hostName\"}" else ""
        val rx = querySingleValue("sum(deriv(system_network_rx_bytes$hf[2m]))", promUrl) ?: 0.0
        val tx = querySingleValue("sum(deriv(system_network_tx_bytes$hf[2m]))", promUrl) ?: 0.0

        return ResourceMetrics(
            status = "STABLE",
            cpuUsage = querySingleValue(q("system_cpu_usage"), promUrl) ?: 0.0,
            memoryUsage = querySingleValue(q("system_memory_usage"), promUrl) ?: 0.0,
            diskUsage = querySingleValue(q("system_disk_usage"), promUrl) ?: 0.0,
            networkTraffic = rx + tx
        )
    }

    fun getDiscoveredHosts(companyId: Long): List<String> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val ip = company.ip ?: return emptyList()
        val uri = UriComponentsBuilder.fromUriString("${prometheusUrl(ip)}/api/v1/label/host_name/values")
            .build(true).toUri()
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            (res.body?.get("data") as? List<String>) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun getContainerMetrics(companyId: Long, containerName: String): ResourceMetrics {
        val company = companyRepository.findById(companyId).orElse(null)
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)
        val ip = company.ip
            ?: return ResourceMetrics(status = "NOT_FOUND", cpuUsage = 0.0, memoryUsage = 0.0, diskUsage = 0.0, networkTraffic = 0.0)
        val promUrl = prometheusUrl(ip)

        val memBytes = querySingleValue("container_memory_usage_bytes{container_name=\"$containerName\"}", promUrl) ?: 0.0
        val totalBytes = querySingleValue("system_memory_total_bytes", promUrl) ?: 0.0
        val memPct = if (totalBytes > 0) (memBytes / totalBytes) * 100.0 else 0.0

        return ResourceMetrics(
            status = "RUNNING",
            cpuUsage = querySingleValue("rate(container_cpu_usage_seconds_total{container_name=\"$containerName\"}[1m]) * 100", promUrl) ?: 0.0,
            memoryUsage = memPct,
            diskUsage = 0.0,
            networkTraffic = 0.0
        )
    }

    fun getLogs(companyId: Long, containerName: String?, severity: String?, keyword: String?, limit: Int, hostName: String? = null): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val ip = company.ip ?: return emptyList()

        val hostFilter = if (!hostName.isNullOrBlank()) ",instance=\"$hostName\"" else ""
        var logQuery = "{job=\"metric-agent\"$hostFilter}"
        if (!containerName.isNullOrBlank() && containerName != "all") logQuery += " |= \"$containerName\""
        if (!keyword.isNullOrBlank()) logQuery += " |= \"(?i)$keyword\""

        val uri = UriComponentsBuilder.fromUriString("${lokiUrl(ip)}/loki/api/v1/query_range")
            .queryParam("query", encodeQuery(logQuery)).queryParam("limit", limit).build(true).toUri()

        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
            val result = (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>> ?: emptyList()
            val logs = mutableListOf<LogEntry>()
            for (stream in result) {
                val streamLabels = stream["stream"] as? Map<*, *> ?: emptyMap<Any, Any>()
                val streamInstance = streamLabels["instance"]?.toString()
                val values = stream["values"] as? List<List<String>> ?: continue
                for (v in values) {
                    val raw = v[1]; var body = raw; var detectedSev = "INFO"
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
                    logs.add(LogEntry(v[0], detectedSev, body, "Docker", "system", containerName, streamInstance, raw))
                }
            }
            if (!severity.isNullOrBlank() && severity != "all") {
                logs.filter { it.severity == severity.uppercase() }.sortedByDescending { it.timestamp }
            } else {
                logs.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun getMonthlyMetrics(
        companyId: Long, year: Int, month: Int?,
        startDate: String?, endDate: String?, hostName: String? = null
    ): MonthlyMetricsResponse {
        val company = companyRepository.findById(companyId).orElse(null)
            ?: return MonthlyMetricsResponse(year, month, startDate, endDate, emptyList())
        val ip = company.ip ?: return MonthlyMetricsResponse(year, month, startDate, endDate, emptyList())
        val promUrl = prometheusUrl(ip)

        fun hq(metric: String) = if (hostName != null) "$metric{host_name=\"$hostName\"}" else metric
        val hhf = if (hostName != null) "{host_name=\"$hostName\"}" else ""
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE

        val (rangeStart, rangeEnd, allDates) = when {
            month != null -> {
                val ym = YearMonth.of(year, month)
                val s = ym.atDay(1); val e = ym.atEndOfMonth()
                Triple(s, e, (0 until ym.lengthOfMonth()).map { s.plusDays(it.toLong()) })
            }
            startDate != null && endDate != null -> {
                val s = LocalDate.parse(startDate, fmt); val e = LocalDate.parse(endDate, fmt)
                val days = generateSequence(s) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.toList()
                Triple(s, e, days)
            }
            else -> {
                val ym = YearMonth.of(year, 1)
                val s = ym.atDay(1); val e = ym.atEndOfMonth()
                Triple(s, e, (0 until ym.lengthOfMonth()).map { s.plusDays(it.toLong()) })
            }
        }

        val epochStart = rangeStart.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val epochEnd   = rangeEnd.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val step = 3600

        val hostCpuPts  = queryRange(hq("system_cpu_usage"),    promUrl, epochStart, epochEnd, step)
        val hostMemPts  = queryRange(hq("system_memory_usage"), promUrl, epochStart, epochEnd, step)
        val hostDiskPts = queryRange(hq("system_disk_usage"),   promUrl, epochStart, epochEnd, step)
        val hostRxPts   = queryRange("sum(deriv(system_network_rx_bytes$hhf[2h]))", promUrl, epochStart, epochEnd, step)
        val hostTxPts   = queryRange("sum(deriv(system_network_tx_bytes$hhf[2h]))", promUrl, epochStart, epochEnd, step)
        val totalMemBytes = querySingleValue(hq("system_memory_total_bytes"), promUrl) ?: 0.0

        val containers = getContainerList(companyId, hostName)
        val containerSeries = containers.associate { c ->
            val id = c.containerId
            Triple(
                queryRange("rate(container_cpu_usage_seconds_total{container_name=\"$id\"}[1h]) * 100", promUrl, epochStart, epochEnd, step),
                queryRange("container_memory_usage_bytes{container_name=\"$id\"}", promUrl, epochStart, epochEnd, step),
                queryRange("rate(container_network_receive_bytes_total{container_name=\"$id\"}[1h])", promUrl, epochStart, epochEnd, step)
            ).let { id to it }
        }

        val alertLogs = try {
            if (!hostName.isNullOrBlank())
                alertRepository.findByMonitoringIdAndHostNameAndCreatedAtBetween(
                    company.monitoringId, hostName, rangeStart.atStartOfDay(), rangeEnd.plusDays(1).atStartOfDay()
                )
            else
                alertRepository.findByMonitoringIdAndCreatedAtBetween(
                    company.monitoringId, rangeStart.atStartOfDay(), rangeEnd.plusDays(1).atStartOfDay()
                )
        } catch (e: Exception) { emptyList() }

        val alertsByDate = alertLogs.groupBy { it.createdAt.toLocalDate() }.mapValues { it.value.size }

        val days = allDates.map { date ->
            val cpuVals  = pointsForDay(hostCpuPts,  date)
            val memVals  = pointsForDay(hostMemPts,  date)
            val diskVals = pointsForDay(hostDiskPts, date)
            val netVals  = mergeNetworkPts(pointsForDay(hostRxPts, date), pointsForDay(hostTxPts, date)).map { it / 1024.0 }
            val hasData = cpuVals.isNotEmpty()
            val hostMetrics = if (hasData) HostDailyMetrics(
                cpu = statsOf(cpuVals), memory = statsOf(memVals),
                disk = statsOf(diskVals), network = statsOf(netVals)
            ) else null
            val containerMetrics = containerSeries.mapNotNull { (id, series) ->
                val (cpuS, memS, netS) = series
                val cCpu = pointsForDay(cpuS, date)
                val cMem = pointsForDay(memS, date).map { if (totalMemBytes > 0) (it / totalMemBytes) * 100.0 else 0.0 }
                val cNet = pointsForDay(netS, date).map { it / 1024.0 }
                if (cCpu.isEmpty() && cMem.isEmpty()) return@mapNotNull null
                ContainerDailyMetrics(containerId = id, status = "RUNNING",
                    cpu = statsOf(cCpu), memory = statsOf(cMem), network = statsOf(cNet))
            }
            DailyMetrics(date = date.toString(), hasData = hasData,
                worstStatus = computeWorstStatus(hasData, cpuVals, diskVals),
                alertCount = alertsByDate[date] ?: 0, host = hostMetrics, containers = containerMetrics)
        }

        return MonthlyMetricsResponse(year, month, startDate, endDate, days)
    }

    fun getAlertsByDate(companyId: Long, date: String, hostName: String? = null): List<com.kpu.backend.domain.alert.AlertLog> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val dayStart = java.time.LocalDate.parse(date).atStartOfDay()
        return if (!hostName.isNullOrBlank())
            alertRepository.findByMonitoringIdAndHostNameAndCreatedAtBetween(company.monitoringId, hostName, dayStart, dayStart.plusDays(1))
        else
            alertRepository.findByMonitoringIdAndCreatedAtBetween(company.monitoringId, dayStart, dayStart.plusDays(1))
    }

    fun getLogsByDateRange(companyId: Long, date: String): List<LogEntry> {
        val company = companyRepository.findById(companyId).orElse(null) ?: return emptyList()
        val ip = company.ip ?: return emptyList()
        val dayStart = java.time.LocalDate.parse(date)
        val kst = ZoneId.of("Asia/Seoul")
        val startNs = dayStart.atStartOfDay(kst).toEpochSecond() * 1_000_000_000L
        val endNs   = dayStart.plusDays(1).atStartOfDay(kst).toEpochSecond() * 1_000_000_000L

        val uri = UriComponentsBuilder.fromUriString("${lokiUrl(ip)}/loki/api/v1/query_range")
            .queryParam("query", encodeQuery("{job=\"metric-agent\"}"))
            .queryParam("start", startNs).queryParam("end", endNs).queryParam("limit", 200)
            .build(true).toUri()

        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
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
                    logs.add(LogEntry(v[0], sev, body, "system", "host", null, company.monitoringId, raw))
                }
            }
            logs.filter { it.severity == "ERROR" || it.severity == "WARN" }.sortedByDescending { it.timestamp }
        } catch (e: Exception) { emptyList() }
    }

    fun queryRangePublic(query: String, prometheusUrl: String, start: Long, end: Long, step: Int) =
        queryRange(query, prometheusUrl, start, end, step)

    fun querySingleValuePublic(query: String, prometheusUrl: String) =
        querySingleValue(query, prometheusUrl)

    private fun encodeQuery(query: String): String =
        java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)

    private fun queryPrometheus(query: String, prometheusUrl: String): List<Map<String, Any>> {
        val uri = UriComponentsBuilder.fromUriString("$prometheusUrl/api/v1/query")
            .queryParam("query", encodeQuery(query)).build(true).toUri()
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
            (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<String, Any>> ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun querySingleValue(query: String, prometheusUrl: String): Double? =
        (queryPrometheus(query, prometheusUrl).firstOrNull()?.get("value") as? List<*>)
            ?.get(1)?.toString()?.toDoubleOrNull()

    private fun queryRange(query: String, prometheusUrl: String, start: Long, end: Long, step: Int): List<Pair<Long, Double>> {
        val uri = UriComponentsBuilder.fromUriString("$prometheusUrl/api/v1/query_range")
            .queryParam("query", encodeQuery(query))
            .queryParam("start", start).queryParam("end", end).queryParam("step", step)
            .build(true).toUri()
        return try {
            val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
            val results = (res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>> ?: return emptyList()
            val values = results.firstOrNull()?.get("values") as? List<*> ?: return emptyList()
            values.mapNotNull { v ->
                val pair = v as? List<*> ?: return@mapNotNull null
                val ts = (pair[0] as? Number)?.toLong() ?: return@mapNotNull null
                val value = pair[1]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                ts to value
            }
        } catch (e: Exception) {
            log.debug("queryRange failed: $query — ${e.message}")
            emptyList()
        }
    }

    private fun pointsForDay(pts: List<Pair<Long, Double>>, date: LocalDate): List<Double> =
        pts.filter { (ts, _) -> Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate() == date }.map { it.second }

    private fun mergeNetworkPts(rx: List<Double>, tx: List<Double>): List<Double> {
        val size = minOf(rx.size, tx.size)
        return if (size == 0) (rx + tx) else (0 until size).map { rx[it] + tx[it] }
    }

    private fun statsOf(vals: List<Double>): MetricStats {
        if (vals.isEmpty()) return MetricStats(0.0, 0.0, 0.0, 0.0)
        return MetricStats(avg = vals.average().round2(), min = vals.min().round2(),
            max = vals.max().round2(), latest = vals.last().round2())
    }

    private fun Double.round2() = Math.round(this * 100.0) / 100.0

    private fun computeWorstStatus(hasData: Boolean, cpuVals: List<Double>, diskVals: List<Double>): String {
        if (!hasData) return "NO_DATA"
        val cpuAvg  = if (cpuVals.isNotEmpty()) cpuVals.average() else 0.0
        val diskAvg = if (diskVals.isNotEmpty()) diskVals.average() else 0.0
        val cpuMax  = if (cpuVals.isNotEmpty()) cpuVals.max() else 0.0
        return when {
            cpuMax > 90.0 || diskAvg > 90.0 -> "CRITICAL"
            cpuAvg > 70.0 || diskAvg > 80.0 -> "WARNING"
            else -> "STABLE"
        }
    }
}
