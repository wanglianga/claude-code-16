package city.market.service

import city.market.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 离线电子秤交易补传：恢复网络时比对本地流水、服务器时间、摊位绑定、封签状态；
 * 离线期间投诉联动人工复核；高峰期异常集中触发重点巡检与信用扣减；
 * 补传后重算窗口内全部交易可信度，并判断是否建议补做抽检。
 */
object OfflineSync {
    /** 每日交易高峰时段（早市/晚市） */
    val PEAK_WINDOWS = listOf(LocalTime.of(7, 0) to LocalTime.of(9, 0), LocalTime.of(17, 0) to LocalTime.of(19, 0))
    const val PEAK_ANOMALY_RATIO = 50   // 离线窗口高峰期占比 ≥50% 视为异常集中
    const val MIN_WINDOW_MINUTES = 30L  // 窗口不足 30 分钟不做高峰判定
    const val CLOCK_DRIFT_WARN_MIN = 5L   // 时钟漂移告警阈值（分钟）
    const val CLOCK_DRIFT_REINSPECT_MIN = 30L // 漂移超过该值建议补做抽检

    data class CsvTx(val ts: LocalDateTime, val weightG: Int, val amount: BigDecimal)

    /** 解析 CSV 流水，每行：`yyyy-MM-dd HH:mm,重量克,金额`（也接受 ISO `T` 分隔与秒）。返回(有效行, 无法解析行数) */
    fun parseCsv(text: String): Pair<List<CsvTx>, Int> {
        var bad = 0
        val out = mutableListOf<CsvTx>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(",", "，").map { it.trim() }
            try {
                val ts = LocalDateTime.parse(parts[0].replace(' ', 'T'))
                val w = parts[1].toInt()
                val amt = parts[2].toBigDecimal()
                require(w in 1..1_000_000) { "bad weight" }
                out += CsvTx(ts, w, amt)
            } catch (e: Exception) { bad++ }
        }
        return out to bad
    }

    /** 离线窗口落在高峰时段的占比（%） */
    fun peakRatio(start: LocalDateTime, end: LocalDateTime): Int {
        val total = Duration.between(start, end).toMinutes()
        if (total <= 0) return 0
        var peak = 0L
        var day = start.toLocalDate()
        while (!day.isAfter(end.toLocalDate())) {
            for ((ps, pe) in PEAK_WINDOWS) {
                val ws = maxOf(start, day.atTime(ps))
                val we = minOf(end, day.atTime(pe))
                if (we.isAfter(ws)) peak += Duration.between(ws, we).toMinutes()
            }
            day = day.plusDays(1)
        }
        return (peak * 100 / total).toInt()
    }

    data class SyncResult(
        val batchId: Int, val anomalies: List<String>, val complaints: Int,
        val accepted: Int, val duplicates: Int, val invalid: Int, val badLines: Int,
        val reevaluated: Int, val pendingReview: Int, val needReinspection: Boolean
    )

    fun process(
        scaleId: Int, uploaderId: Int,
        offlineStart: LocalDateTime, offlineEnd: LocalDateTime,
        deviceClock: LocalDateTime?, sealAtSync: String, csv: String
    ): SyncResult {
        require(offlineEnd.isAfter(offlineStart)) { "恢复时间必须晚于离线开始时间" }
        val (rows, badLines) = parseCsv(csv)
        require(rows.isNotEmpty()) { "未解析到有效交易行（格式：yyyy-MM-dd HH:mm,重量克,金额）" }
        return transaction {
            val scale = Scales.selectAll().where { Scales.id eq scaleId }.single()
            val stallId = scale[Scales.stallId]
            val now = LocalDateTime.now()
            val anomalies = mutableListOf<String>()

            // —— 比对 1：服务器时间（设备本地时钟漂移） ——
            val drift = deviceClock?.let { kotlin.math.abs(Duration.between(it, now).toMinutes()) } ?: 0L
            if (drift > CLOCK_DRIFT_WARN_MIN) anomalies += "CLOCK_DRIFT"
            // —— 比对 2：摊位绑定（离线开始后才绑定到当前摊位 → 换绑风险） ——
            if (scale[Scales.boundAt].isAfter(offlineStart)) anomalies += "BINDING_CHANGED"
            // —— 比对 3：封签状态 ——
            if (sealAtSync == "BROKEN") anomalies += "SEAL_BROKEN"
            // —— 比对 4：高峰期集中 ——
            val ratio = peakRatio(offlineStart, offlineEnd)
            val windowMin = Duration.between(offlineStart, offlineEnd).toMinutes()
            if (ratio >= PEAK_ANOMALY_RATIO && windowMin >= MIN_WINDOW_MINUTES) anomalies += "PEAK_CONCENTRATED"

            // —— 比对 5：本地流水 vs 服务器（重复/越界检测）与入库 ——
            var accepted = 0; var duplicates = 0; var invalid = 0
            rows.forEach { tx ->
                when {
                    tx.ts.isBefore(offlineStart) || tx.ts.isAfter(offlineEnd) -> invalid++
                    Transactions.selectAll().where {
                        (Transactions.scaleId eq scaleId) and (Transactions.ts eq tx.ts) and (Transactions.weightG eq tx.weightG)
                    }.any() -> duplicates++
                    else -> {
                        Transactions.insert {
                            it[Transactions.scaleId] = scaleId; it[Transactions.stallId] = stallId
                            it[ts] = tx.ts; it[weightG] = tx.weightG; it[amountYuan] = tx.amount
                            it[offline] = true
                        }
                        accepted++
                    }
                }
            }

            // —— 离线期间投诉 → 相关交易进入人工复核 ——
            val complaints = Complaints.selectAll().where {
                (Complaints.stallId eq stallId) and
                    (Complaints.purchaseTime greaterEq offlineStart) and (Complaints.purchaseTime lessEq offlineEnd)
            }.toList()
            complaints.forEach { c ->
                val flags = (c[Complaints.trustFlags] ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
                if (flags.add("OFFLINE_PERIOD")) {
                    Complaints.update({ Complaints.id eq c[Complaints.id] }) {
                        it[trustFlags] = flags.joinToString(",")
                    }
                }
            }

            // —— 补传后重算窗口内全部交易（含服务器原有离线流水）可信度 ——
            val needReview = complaints.isNotEmpty() || anomalies.isNotEmpty()
            val flagStr = (listOf("OFFLINE_PERIOD") + anomalies).joinToString(",")
            val windowTxs = Transactions.selectAll().where {
                (Transactions.scaleId eq scaleId) and
                    (Transactions.ts greaterEq offlineStart) and (Transactions.ts lessEq offlineEnd)
            }.toList()
            windowTxs.forEach { tx ->
                Transactions.update({ Transactions.id eq tx[Transactions.id] }) {
                    it[trust] = if (needReview) "PENDING_REVIEW" else "TRUSTED"
                    it[trustFlags] = flagStr
                }
            }
            val pendingReview = if (needReview) windowTxs.size else 0

            // —— 是否需要补做抽检 ——
            val needReinspection = complaints.isNotEmpty() || "SEAL_BROKEN" in anomalies ||
                "BINDING_CHANGED" in anomalies || "PEAK_CONCENTRATED" in anomalies || drift > CLOCK_DRIFT_REINSPECT_MIN

            // —— 高峰期异常集中：重点巡检任务 + 摊位信用 ——
            if ("PEAK_CONCENTRATED" in anomalies) {
                createTaskIfNone(scaleId, stallId, "离线时间异常集中在高峰期（占比${ratio}%），重点巡检", 80)
                Stalls.update({ Stalls.id eq stallId }) {
                    with(SqlExpressionBuilder) { it.update(creditScore, creditScore - 5) }
                }
                Db.logEvent(scaleId, "OFFLINE_ANOMALY", "离线时间异常集中在高峰期（占比${ratio}%），已生成重点巡检任务，摊位信用 -5")
            }
            // —— 补做抽检提示 ——
            if (needReinspection) {
                createTaskIfNone(scaleId, stallId, "离线补传后需补做抽检（${anomalies.joinToString("/").ifBlank { "离线期间有投诉" }}）", 60)
                Db.logEvent(scaleId, "REINSPECT_SUGGESTED", "离线补传分析建议补做抽检")
            }

            // —— 恢复联网 ——
            if (!scale[Scales.online]) {
                Scales.update({ Scales.id eq scaleId }) { it[online] = true }
                Db.logEvent(scaleId, "ONLINE", "补传完成，设备恢复联网")
            }
            Db.logEvent(
                scaleId, "OFFLINE_SYNC",
                "离线交易补传：窗口 $offlineStart ~ $offlineEnd，上传 ${rows.size} 笔，入库 $accepted 笔，重复 $duplicates，越界 $invalid" +
                    (if (pendingReview > 0) "，$pendingReview 笔进入人工复核" else "")
            )

            val batchId = OfflineSyncs.insert {
                it[OfflineSyncs.scaleId] = scaleId; it[OfflineSyncs.stallId] = stallId
                it[uploadedBy] = uploaderId
                it[OfflineSyncs.offlineStart] = offlineStart; it[OfflineSyncs.offlineEnd] = offlineEnd
                it[OfflineSyncs.deviceClock] = deviceClock; it[clockDriftMin] = drift.toInt()
                it[OfflineSyncs.sealAtSync] = sealAtSync
                it[txUploaded] = rows.size; it[txAccepted] = accepted
                it[txDuplicate] = duplicates; it[txInvalid] = invalid
                it[txReevaluated] = windowTxs.size; it[OfflineSyncs.pendingReview] = pendingReview
                it[peakRatio] = ratio; it[OfflineSyncs.anomalies] = anomalies.joinToString(",")
                it[complaintsInWindow] = complaints.size
                it[OfflineSyncs.needReinspection] = needReinspection
                it[createdAt] = now
            } get OfflineSyncs.id

            SyncResult(batchId, anomalies, complaints.size, accepted, duplicates, invalid, badLines, windowTxs.size, pendingReview, needReinspection)
        }
    }

    private fun createTaskIfNone(scaleId: Int, stallId: Int, reason: String, score: Int) {
        val pending = InspectionTasks.selectAll().where {
            (InspectionTasks.scaleId eq scaleId) and (InspectionTasks.status eq "PENDING")
        }.count()
        if (pending == 0L) {
            InspectionTasks.insert {
                it[InspectionTasks.scaleId] = scaleId; it[InspectionTasks.stallId] = stallId
                it[InspectionTasks.reason] = reason; it[InspectionTasks.score] = score
                it[generatedBy] = "RULE"; it[createdAt] = LocalDateTime.now()
            }
        }
    }

    /** 人工复核：通过 → 批次 REVIEWED、交易 REVIEWED_OK；可疑 → FLAGGED、交易 SUSPICIOUS、摊位信用再扣 */
    fun review(batchId: Int, pass: Boolean) = transaction {
        val batch = OfflineSyncs.selectAll().where { OfflineSyncs.id eq batchId }.single()
        val scaleId = batch[OfflineSyncs.scaleId]
        val newTrust = if (pass) "REVIEWED_OK" else "SUSPICIOUS"
        Transactions.update({
            (Transactions.scaleId eq scaleId) and
                (Transactions.ts greaterEq batch[OfflineSyncs.offlineStart]) and
                (Transactions.ts lessEq batch[OfflineSyncs.offlineEnd]) and
                (Transactions.trust eq "PENDING_REVIEW")
        }) { it[trust] = newTrust }
        OfflineSyncs.update({ OfflineSyncs.id eq batchId }) {
            it[status] = if (pass) "REVIEWED" else "FLAGGED"
            it[pendingReview] = 0
        }
        if (!pass) {
            Stalls.update({ Stalls.id eq batch[OfflineSyncs.stallId] }) {
                with(SqlExpressionBuilder) { it.update(creditScore, creditScore - 5) }
            }
        }
        Db.logEvent(
            scaleId, "MANUAL_REVIEW",
            "离线补传批次 #$batchId 人工复核${if (pass) "通过" else "标记可疑"}" + if (pass) "" else "，摊位信用 -5"
        )
    }

    fun anomalyLabel(a: String) = when (a) {
        "PEAK_CONCENTRATED" -> "高峰期离线集中"
        "CLOCK_DRIFT" -> "设备时钟漂移"
        "SEAL_BROKEN" -> "封签破损"
        "BINDING_CHANGED" -> "离线期间换绑摊位"
        "OFFLINE_PERIOD" -> "离线期间交易"
        else -> a
    }
}
