package com.kpu.backend.domain.security.service

import com.kpu.backend.domain.company.repository.CompanyRepository
import com.kpu.backend.domain.security.dto.*
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class SecurityServiceImpl(
    private val companyRepository: CompanyRepository,
    private val restTemplate: RestTemplate
) : SecurityService {

    private val log = LoggerFactory.getLogger(SecurityServiceImpl::class.java)
    private val kst = ZoneId.of("Asia/Seoul")
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(kst)
    private val failedPwRegex =
        """Failed password for (?:invalid user )?(\S+) from (\d{1,3}(?:\.\d{1,3}){3}) port""".toRegex()

    private fun encode(q: String) = URLEncoder.encode(q, StandardCharsets.UTF_8)

    override fun getSshStats(companyId: Long, days: Int): SshStatsResponse {
        val company = companyRepository.findById(companyId).orElse(null)
        val ip = company?.ip
        if (ip == null) return staticFallback()

        val lokiBase = "http://$ip:3100"
        val nowSec = Instant.now().epochSecond
        val startSec = nowSec - days * 86400L
        val todayStart = Instant.now().atZone(kst).toLocalDate()
            .atStartOfDay(kst).toEpochSecond()
        val hourStart = nowSec - 3600L

        return try {
            // 1. Daily counts (metric range query, 1-day step)
            val dailyCounts = queryCountOverTime(lokiBase, startSec, nowSec, step = 86400)
                .map { (ts, cnt) ->
                    DailyAttackCount(
                        date = dayFmt.format(Instant.ofEpochSecond(ts)),
                        count = cnt
                    )
                }

            // 2. Hourly counts (last 7 days, 1-hour step)
            val hourlyRawPts = queryCountOverTime(lokiBase, nowSec - 7 * 86400L, nowSec, step = 3600)
            val hourlyMap = LongArray(24)
            for ((ts, cnt) in hourlyRawPts) {
                val h = Instant.ofEpochSecond(ts).atZone(kst).hour
                hourlyMap[h] += cnt
            }
            val hourlyCounts = hourlyMap.mapIndexed { h, cnt -> HourlyAttackCount(h, cnt) }

            // 3. Recent log lines for IP / username analysis (last 7 days, limit 3000)
            val recentLines = fetchSshLogLines(lokiBase, nowSec - 7 * 86400L, nowSec, limit = 3000)
            val ipMap = mutableMapOf<String, Long>()
            val userMap = mutableMapOf<String, Long>()
            val ipUserMap = mutableMapOf<String, MutableMap<String, Long>>()

            for (line in recentLines) {
                val m = failedPwRegex.find(line) ?: continue
                val user = m.groupValues[1]
                val srcIp = m.groupValues[2]
                ipMap[srcIp] = (ipMap[srcIp] ?: 0L) + 1
                userMap[user] = (userMap[user] ?: 0L) + 1
                val um = ipUserMap.getOrPut(srcIp) { mutableMapOf() }
                um[user] = (um[user] ?: 0L) + 1
            }

            val topIps = ipMap.entries.sortedByDescending { it.value }.take(15).map { (ip2, cnt) ->
                val topUser = ipUserMap[ip2]?.maxByOrNull { it.value }?.key
                IpAttackCount(ip2, cnt, topUser)
            }
            val topUsers = userMap.entries.sortedByDescending { it.value }.take(15)
                .map { (u, c) -> UsernameAttackCount(u, c) }

            // 4. Today count
            val todayCount = queryCountTotal(lokiBase, todayStart, nowSec)
            val lastHourCount = queryCountTotal(lokiBase, hourStart, nowSec)

            val total = dailyCounts.sumOf { it.count }
            val avgPerSec = if (days > 0) total.toDouble() / (days * 86400) else 0.0
            val peakPerSec = hourlyCounts.maxOfOrNull { it.count }?.toDouble()?.div(3600) ?: avgPerSec

            val capacity = buildCapacity(peakPerSec)

            SshStatsResponse(
                totalAttempts = total,
                periodStart = dayFmt.format(Instant.ofEpochSecond(startSec)),
                periodEnd = dayFmt.format(Instant.now()),
                todayCount = todayCount,
                lastHourCount = lastHourCount,
                avgPerSec = avgPerSec,
                dailyCounts = dailyCounts,
                hourlyCounts = hourlyCounts,
                topIps = topIps,
                topUsernames = topUsers,
                capacityAnalysis = capacity,
                dataSource = "loki"
            )
        } catch (e: Exception) {
            log.warn("Loki auth-log query failed: ${e.message}. Using static fallback.")
            staticFallback()
        }
    }

    private fun queryCountOverTime(lokiBase: String, startSec: Long, endSec: Long, step: Int): List<Pair<Long, Long>> {
        val uri = UriComponentsBuilder.fromUriString("$lokiBase/loki/api/v1/query_range")
            .queryParam("query", encode("""count_over_time({job="auth-log"} |= "Failed password" [${step}s])"""))
            .queryParam("start", startSec)
            .queryParam("end", endSec)
            .queryParam("step", step)
            .build(true).toUri()

        val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
        val results = ((res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>>) ?: emptyList()
        val out = mutableListOf<Pair<Long, Long>>()
        for (r in results) {
            for (v in (r["values"] as? List<List<*>>) ?: emptyList()) {
                val ts = v[0].toString().toLongOrNull() ?: continue
                val cnt = v[1].toString().toLongOrNull() ?: continue
                out.add(ts to cnt)
            }
        }
        return out
    }

    private fun queryCountTotal(lokiBase: String, startSec: Long, endSec: Long): Long {
        if (endSec <= startSec) return 0L
        val rangeS = endSec - startSec
        return try {
            queryCountOverTime(lokiBase, startSec, endSec, rangeS.toInt()).sumOf { it.second }
        } catch (e: Exception) { 0L }
    }

    private fun fetchSshLogLines(lokiBase: String, startNs: Long, endSec: Long, limit: Int): List<String> {
        val uri = UriComponentsBuilder.fromUriString("$lokiBase/loki/api/v1/query_range")
            .queryParam("query", encode("""{job="auth-log"} |= "Failed password""""))
            .queryParam("start", startNs)
            .queryParam("end", endSec)
            .queryParam("limit", limit)
            .queryParam("direction", "backward")
            .build(true).toUri()

        val res = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Unit>(HttpHeaders()), Map::class.java)
        val results = ((res.body?.get("data") as? Map<*, *>)?.get("result") as? List<Map<*, *>>) ?: emptyList()
        return results.flatMap { stream ->
            (stream["values"] as? List<List<*>> ?: emptyList()).map { v -> v[1].toString() }
        }
    }

    private fun buildCapacity(peakPerSec: Double): CapacityAnalysis {
        val cores = 16
        val bcryptMs = 100
        val maxStartups = 100
        val poolFillRate = maxStartups * 1000 / bcryptMs  // ~1000/sec to fill pool
        val cpuAtPool = maxStartups.toDouble() * bcryptMs / (cores * 1000) * 100
        val saturationRate = cores * 1000 / bcryptMs
        val currentCpu = minOf(peakPerSec * bcryptMs / (cores * 1000) * 100, 100.0)
        val risk = when {
            peakPerSec >= saturationRate -> "CRITICAL"
            peakPerSec >= 33 -> "HIGH"
            peakPerSec >= 5 -> "MEDIUM"
            else -> "LOW"
        }
        return CapacityAnalysis(
            coresAvailable = cores,
            bcryptMsPerAttempt = bcryptMs,
            maxStartupsDefault = maxStartups,
            poolFillRatePerSec = poolFillRate,
            cpuAtPoolFullPct = cpuAtPool,
            cpuSaturationPerSec = saturationRate,
            currentRatePerSec = peakPerSec,
            currentCpuLoadPct = currentCpu,
            riskLevel = risk
        )
    }

    private fun staticFallback(): SshStatsResponse {
        val dailyCounts = listOf(
            DailyAttackCount("2026-05-24", 9839), DailyAttackCount("2026-05-25", 10241),
            DailyAttackCount("2026-05-26", 9901), DailyAttackCount("2026-05-27", 10120),
            DailyAttackCount("2026-05-28", 9812), DailyAttackCount("2026-05-29", 9732),
            DailyAttackCount("2026-05-30", 9227), DailyAttackCount("2026-05-31", 5032),
            DailyAttackCount("2026-06-01", 5104), DailyAttackCount("2026-06-02", 5089),
            DailyAttackCount("2026-06-03", 5043), DailyAttackCount("2026-06-04", 5001),
            DailyAttackCount("2026-06-05", 4993), DailyAttackCount("2026-06-06", 4964),
            DailyAttackCount("2026-06-07", 4673), DailyAttackCount("2026-06-08", 4701),
            DailyAttackCount("2026-06-09", 4678), DailyAttackCount("2026-06-10", 4683),
            DailyAttackCount("2026-06-11", 4621), DailyAttackCount("2026-06-12", 4688),
            DailyAttackCount("2026-06-13", 4669), DailyAttackCount("2026-06-14", 4101),
            DailyAttackCount("2026-06-15", 4098), DailyAttackCount("2026-06-16", 4100),
            DailyAttackCount("2026-06-17", 4090), DailyAttackCount("2026-06-18", 4078),
            DailyAttackCount("2026-06-19", 4080), DailyAttackCount("2026-06-20", 4056),
            DailyAttackCount("2026-06-21", 3556), DailyAttackCount("2026-06-22", 3551),
            DailyAttackCount("2026-06-23", 3547), DailyAttackCount("2026-06-24", 3548),
            DailyAttackCount("2026-06-25", 3549), DailyAttackCount("2026-06-26", 544)
        )
        val hourlyCounts = listOf(
            0 to 2840L, 1 to 3210L, 2 to 3540L, 3 to 4120L, 4 to 3980L, 5 to 3201L,
            6 to 2100L, 7 to 1800L, 8 to 1950L, 9 to 2100L, 10 to 2300L, 11 to 2500L,
            12 to 2200L, 13 to 2150L, 14 to 2300L, 15 to 2100L, 16 to 1900L, 17 to 1850L,
            18 to 2000L, 19 to 2200L, 20 to 2450L, 21 to 2600L, 22 to 2750L, 23 to 2800L
        ).map { HourlyAttackCount(it.first, it.second) }

        val topIps = listOf(
            IpAttackCount("120.26.202.34", 2903, "admin"),
            IpAttackCount("87.251.64.144", 2886, "root"),
            IpAttackCount("87.251.64.145", 1867, "root"),
            IpAttackCount("112.216.129.27", 854, "ubuntu"),
            IpAttackCount("213.209.159.235", 624, "admin"),
            IpAttackCount("213.209.159.234", 621, "user"),
            IpAttackCount("213.209.159.233", 617, "deploy"),
            IpAttackCount("213.209.159.236", 615, "admin"),
            IpAttackCount("213.209.159.237", 609, "test"),
            IpAttackCount("213.209.159.238", 601, "root"),
            IpAttackCount("103.154.231.122", 573, "admin"),
            IpAttackCount("185.100.212.141", 474, "ubuntu")
        )
        val topUsers = listOf(
            UsernameAttackCount("admin", 294), UsernameAttackCount("ubuntu", 184),
            UsernameAttackCount("user", 147), UsernameAttackCount("administrator", 94),
            UsernameAttackCount("deploy", 88), UsernameAttackCount("ubnt", 87),
            UsernameAttackCount("test", 74), UsernameAttackCount("ftpuser", 73),
            UsernameAttackCount("minecraft", 71), UsernameAttackCount("plex", 60)
        )

        val avgPerSec = 186809.0 / (33 * 86400)
        return SshStatsResponse(
            totalAttempts = 186809,
            periodStart = "2026-05-24",
            periodEnd = "2026-06-26",
            todayCount = 544,
            lastHourCount = 21,
            avgPerSec = avgPerSec,
            dailyCounts = dailyCounts,
            hourlyCounts = hourlyCounts,
            topIps = topIps,
            topUsernames = topUsers,
            capacityAnalysis = buildCapacity(hourlyCounts.maxOf { it.count }.toDouble() / 3600),
            dataSource = "static"
        )
    }
}
