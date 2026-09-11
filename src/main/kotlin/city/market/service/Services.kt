package city.market.service

import city.market.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

object Rules {
    /** 抽检允许误差阈值（%），超过即判定超标 */
    const val ERROR_THRESHOLD_PCT = 0.5

    /** 投诉短少判定：短少量超过 max(标称*0.5%, 5g) 视为疑似短斤少两 */
    const val SHORTFALL_PCT = 0.5
    const val SHORTFALL_MIN_G = 5

    /** 换秤判定窗口：绑定时间距投诉不足 N 天视为“临时更换” */
    const val SWAP_WINDOW_DAYS = 7L

    /** 生成抽检任务的最低风险分 */
    const val TASK_SCORE_THRESHOLD = 30
}

// ---------------------------------------------------------------------------
// 抽检任务生成：根据检定到期、投诉次数、交易峰值、摊位历史处罚评分
// ---------------------------------------------------------------------------
object TaskGenerator {
    data class Generated(val scaleId: Int, val deviceNo: String, val score: Int, val reason: String)

    fun preview(): List<Generated> = transaction {
        Scales.selectAll().where { Scales.status eq "ACTIVE" }.mapNotNull { scale ->
            val factors = mutableListOf<String>()
            var score = 0
            val scaleId = scale[Scales.id]
            val stallId = scale[Scales.stallId]
            val now = LocalDateTime.now()

            // 1) 检定证书到期
            val daysToExpiry = LocalDate.now().toEpochDay().let { scale[Scales.certValidUntil].toEpochDay() - it }
            when {
                daysToExpiry < 0 -> { score += 50; factors += "检定证书已过期${-daysToExpiry}天(+50)" }
                daysToExpiry <= 30 -> { score += 30; factors += "检定证书${daysToExpiry}天内到期(+30)" }
                daysToExpiry <= 90 -> { score += 10; factors += "检定证书90天内到期(+10)" }
            }
            // 2) 近 90 天投诉次数
            val complaints90 = Complaints.selectAll().where {
                (Complaints.stallId eq stallId) and (Complaints.createdAt greaterEq now.minusDays(90))
            }.count()
            when {
                complaints90 >= 3 -> { score += 30; factors += "近90天投诉${complaints90}次(+30)" }
                complaints90 >= 1 -> { score += 15; factors += "近90天投诉${complaints90}次(+15)" }
            }
            // 3) 交易峰值（近 30 天日均交易量）
            val txCount = Transactions.selectAll().where {
                (Transactions.scaleId eq scaleId) and (Transactions.ts greaterEq now.minusDays(30))
            }.count()
            val avgDaily = txCount / 30
            when {
                avgDaily >= 100 -> { score += 15; factors += "交易峰值日均${avgDaily}笔(+15)" }
                avgDaily >= 50 -> { score += 8; factors += "交易峰值日均${avgDaily}笔(+8)" }
            }
            // 4) 摊位历史处罚
            val penalties = Stalls.selectAll().where { Stalls.id eq stallId }.single()[Stalls.penaltyCount]
            when {
                penalties >= 2 -> { score += 25; factors += "历史处罚${penalties}次(+25)" }
                penalties == 1 -> { score += 10; factors += "历史处罚1次(+10)" }
            }
            // 5) 离线设备加分
            if (!scale[Scales.online]) { score += 10; factors += "设备离线(+10)" }

            if (score >= Rules.TASK_SCORE_THRESHOLD) {
                Generated(scaleId, scale[Scales.deviceNo], score, factors.joinToString("；"))
            } else null
        }.sortedByDescending { it.score }
    }

    /** 为所有达到阈值且没有待办任务的秤生成抽检任务，返回生成数 */
    fun generate(): Int = transaction {
        var created = 0
        preview().forEach { g ->
            val pending = InspectionTasks.selectAll().where {
                (InspectionTasks.scaleId eq g.scaleId) and (InspectionTasks.status eq "PENDING")
            }.count()
            if (pending == 0L) {
                val stallId = Scales.selectAll().where { Scales.id eq g.scaleId }.single()[Scales.stallId]
                InspectionTasks.insert {
                    it[scaleId] = g.scaleId; it[InspectionTasks.stallId] = stallId
                    it[reason] = g.reason; it[score] = g.score
                    it[generatedBy] = "RULE"; it[createdAt] = LocalDateTime.now()
                }
                created++
            }
        }
        created
    }
}

// ---------------------------------------------------------------------------
// 投诉交易匹配与可信度判断
// ---------------------------------------------------------------------------
object Trust {
    data class Assessment(
        val scaleId: Int?,
        val matchedTxId: Long?,
        val flags: List<String>,
        val shortfallG: Int,
        val suspected: Boolean
    ) {
        val trusted get() = flags.isEmpty()
    }

    fun flagLabel(f: String) = when (f) {
        "CERT_EXPIRED" -> "检定证书过期"
        "OFFLINE" -> "交易时设备离线"
        "SCALE_SWAPPED" -> "摊位临时更换电子秤"
        "SHARED_SCALE" -> "多人共用一台秤"
        "NO_MATCHED_TX" -> "未匹配到当天交易"
        else -> f
    }

    /** 匹配摊位当前秤与当天交易，评估交易可信度 */
    fun assess(stallId: Int, purchaseTime: LocalDateTime, nominalWeightG: Int, reweighedWeightG: Int): Assessment =
        transaction {
            // 找秤：优先购买时段排班命中该摊位的共用秤；否则取本摊位备案秤
            val own = Scales.selectAll().where { Scales.stallId eq stallId }
                .orderBy(Scales.boundAt, SortOrder.DESC).toList()
            val sharedScaleIds = ScaleSchedules.selectAll().where { ScaleSchedules.stallId eq stallId }
                .map { it[ScaleSchedules.scaleId] }
            val shared = if (sharedScaleIds.isEmpty()) emptyList()
            else Scales.selectAll().where { Scales.id inList sharedScaleIds }.toList()
            val hour = purchaseTime.hour
            val covering = (own + shared).filter { sc ->
                ScaleSchedules.selectAll().where {
                    (ScaleSchedules.scaleId eq sc[Scales.id]) and (ScaleSchedules.stallId eq stallId) and
                        (ScaleSchedules.startHour lessEq hour) and (ScaleSchedules.endHour greater hour)
                }.any()
            }
            val scale = covering.firstOrNull() ?: own.firstOrNull() ?: shared.firstOrNull()
            val flags = mutableListOf<String>()
            var matchedTxId: Long? = null

            if (scale != null) {
                val scaleId = scale[Scales.id]
                // 共用秤排班摊位数 > 1 也视为多人共用
                val sharedBySchedules = ScaleSchedules.selectAll().where { ScaleSchedules.scaleId eq scaleId }
                    .map { it[ScaleSchedules.stallId] }.distinct().size > 1
                // 证书过期（按购买时间判断）
                if (purchaseTime.toLocalDate().isAfter(scale[Scales.certValidUntil])) flags += "CERT_EXPIRED"
                // 设备离线
                if (!scale[Scales.online]) flags += "OFFLINE"
                // 临时换秤：绑定时间距购买时间不足 SWAP_WINDOW_DAYS（仅备案在本摊位的秤参与换秤判定）
                if (scale[Scales.stallId] == stallId &&
                    scale[Scales.boundAt].isAfter(purchaseTime.minusDays(Rules.SWAP_WINDOW_DAYS))) flags += "SCALE_SWAPPED"
                // 多人共用
                if (scale[Scales.shared] || sharedBySchedules) flags += "SHARED_SCALE"
                // 匹配当天交易：同秤、同一天、重量与标称接近（±15%），取时间最近一笔
                val dayStart = purchaseTime.toLocalDate().atStartOfDay()
                val tx = Transactions.selectAll().where {
                    (Transactions.scaleId eq scaleId) and
                        (Transactions.ts greaterEq dayStart) and
                        (Transactions.ts less dayStart.plusDays(1)) and
                        (Transactions.weightG greaterEq (nominalWeightG * 0.85).toInt()) and
                        (Transactions.weightG lessEq (nominalWeightG * 1.15).toInt())
                }.orderBy(Transactions.ts, SortOrder.DESC).limit(1).singleOrNull()
                if (tx != null) {
                    matchedTxId = tx[Transactions.id]
                    if (tx[Transactions.offline] && "OFFLINE" !in flags) flags += "OFFLINE"
                } else {
                    flags += "NO_MATCHED_TX"
                }
            } else {
                flags += "NO_MATCHED_TX"
            }

            val shortfall = nominalWeightG - reweighedWeightG
            val threshold = maxOf(nominalWeightG * Rules.SHORTFALL_PCT / 100.0, Rules.SHORTFALL_MIN_G.toDouble())
            Assessment(scale?.get(Scales.id), matchedTxId, flags, shortfall, shortfall > threshold)
        }
}

// ---------------------------------------------------------------------------
// 处罚联动：信用、抽检频次、公示
// ---------------------------------------------------------------------------
object PenaltyEffects {
    /**
     * 确认处罚：扣摊位/市场信用、提高抽检频次、生成公示、办结关联投诉。
     * 共用秤责任拆分的处罚（同一 splitGroup）一次性整组确认，
     * 设备管理责任与经营短斤责任分别作用于各自责任摊位、分别发布分类公示。
     */
    fun confirm(penaltyId: Int) = transaction {
        val first = Penalties.selectAll().where { Penalties.id eq penaltyId }.single()
        val group = first[Penalties.splitGroup]
        val siblings = if (group == null) listOf(first)
        else Penalties.selectAll().where { Penalties.splitGroup eq group }.toList()
        // 整组待确认处罚一并确认（已撤销/已确认的跳过）
        siblings.filter { it[Penalties.status] == "ISSUED" || it[Penalties.status] == "APPEALING" }
            .forEach { confirmSingle(it[Penalties.id]) }
    }

    private fun confirmSingle(penaltyId: Int) {
        val p = Penalties.selectAll().where { Penalties.id eq penaltyId }.single()
        val stallId = p[Penalties.stallId]
        val now = LocalDateTime.now()
        val kind = p[Penalties.kind]
        Penalties.update({ Penalties.id eq penaltyId }) {
            it[status] = "CONFIRMED"; it[confirmedAt] = now
        }
        // 摊位信用与处罚计数（设备责任扣备案摊位，经营责任扣实际经营摊位）
        Stalls.update({ Stalls.id eq stallId }) {
            with(SqlExpressionBuilder) {
                it.update(creditScore, creditScore - 10)
                it.update(penaltyCount, penaltyCount + 1)
            }
        }
        val stall = Stalls.selectAll().where { Stalls.id eq stallId }.single()
        val marketId = stall[Stalls.marketId]
        // 市场信用
        Markets.update({ Markets.id eq marketId }) {
            with(SqlExpressionBuilder) { it.update(creditScore, creditScore - 2) }
        }
        // 抽检频次：市场近 180 天确认处罚 >= 3 起 → HIGH
        val confirmed = Penalties.join(Stalls, JoinType.INNER, Penalties.stallId, Stalls.id)
            .selectAll().where {
            (Stalls.marketId eq marketId) and (Penalties.status eq "CONFIRMED") and
                (Penalties.confirmedAt greaterEq now.minusDays(180))
        }.count()
        if (confirmed >= 3) {
            Markets.update({ Markets.id eq marketId }) { it[inspectionLevel] = "HIGH" }
        }
        val stallNo = stall[Stalls.stallNo]
        val market = Markets.selectAll().where { Markets.id eq marketId }.single()
        // 分类公示：区分设备管理问题与经营行为问题，避免处罚对象模糊
        val scaleRegStall = p[Penalties.scaleId]?.let { sid ->
            Scales.selectAll().where { Scales.id eq sid }.singleOrNull()?.let { dev ->
                Stalls.selectAll().where { Stalls.id eq dev[Scales.stallId] }.single()[Stalls.stallNo]
            }
        }
        val (title, content) = if (kind == "DEVICE") {
            "【设备管理问题】${market[Markets.name]} $stallNo 摊位设备管理责任公示" to
                ("处罚类型：设备管理责任（电子秤封签/检定/维护管理不到位，由设备备案摊位承担）。" +
                    "处罚事由：${p[Penalties.reason]}；罚款金额：${p[Penalties.amount]} 元。" +
                    (if (p[Penalties.operatorStallId] != null) "本公示仅针对设备管理问题，相关经营短斤少两行为已另行认定公示。" else ""))
        } else {
            "【经营行为问题】${market[Markets.name]} $stallNo 摊位短斤少两处罚公示" to
                ("处罚类型：经营短斤少两责任（由实际经营者承担）。处罚事由：${p[Penalties.reason]}；罚款金额：${p[Penalties.amount]} 元。" +
                    (if (scaleRegStall != null && scaleRegStall != stallNo) "涉事电子秤备案摊位为 $scaleRegStall，经付款码、排班、交易时间、商品品类、监控备注认定实际经营摊位为 $stallNo。" else "") +
                    (if (p[Penalties.splitGroup] != null) "本投诉责任已拆分：设备管理问题与经营行为问题分别记录、分别公示。" else ""))
        }
        Disclosures.insert {
            it[Disclosures.marketId] = marketId; it[Disclosures.stallId] = stallId
            it[Disclosures.penaltyId] = penaltyId
            it[Disclosures.kind] = kind
            it[Disclosures.title] = title; it[Disclosures.content] = content
            it[publishedAt] = now
        }
        // 关联投诉办结（仅经营责任处罚携带 complaintId）
        p[Penalties.complaintId]?.let { cid ->
            Complaints.update({ Complaints.id eq cid }) { it[status] = "RESOLVED" }
        }
        // 秤档案
        p[Penalties.scaleId]?.let { sid ->
            val typeLabel = if (kind == "DEVICE") "设备管理责任确认：罚款 ${p[Penalties.amount]} 元（备案摊位承担），摊位信用 -10，市场信用 -2"
            else "经营短斤责任确认：罚款 ${p[Penalties.amount]} 元（实际经营者承担），摊位信用 -10，市场信用 -2"
            Db.logEvent(sid, "PENALTY_CONFIRMED", typeLabel)
        }
    }
}

// ---------------------------------------------------------------------------
// 报表：处罚后变化追踪 + 年度治理材料
// ---------------------------------------------------------------------------
object Reports {
    data class DimensionRow(
        val key: String,
        val complaintsBefore: Long, val verifiedBefore: Long,
        val complaintsAfter: Long, val verifiedAfter: Long,
        val penalties: Long, val fineTotal: BigDecimal
    )

    /** 以“首次确认处罚”为分界，对比前后 90 天投诉变化。dimension: market/stall/device/complainant */
    fun tracking(dimension: String): List<DimensionRow> = transaction {
        val confirmed = Penalties.selectAll().where { Penalties.status eq "CONFIRMED" }.toList()
        if (confirmed.isEmpty()) return@transaction emptyList()

        data class Group(val key: String, val stallIds: Set<Int>, val scaleIds: Set<Int>, val consumerIds: Set<Int>, val pivot: LocalDateTime)

        val groups = mutableMapOf<String, Group>()
        fun keyOf(p: ResultRow): Pair<String, Set<Int>>? {
            val stallId = p[Penalties.stallId]
            val stall = Stalls.selectAll().where { Stalls.id eq stallId }.single()
            return when (dimension) {
                "market" -> {
                    val m = Markets.selectAll().where { Markets.id eq stall[Stalls.marketId] }.single()
                    "市场:${m[Markets.name]}" to Stalls.selectAll().where { Stalls.marketId eq m[Markets.id] }.map { it[Stalls.id] }.toSet()
                }
                "stall" -> "摊位:${stall[Stalls.stallNo]}" to setOf(stallId)
                "device" -> {
                    val sid = p[Penalties.scaleId] ?: return null
                    val dev = Scales.selectAll().where { Scales.id eq sid }.single()[Scales.deviceNo]
                    "设备:$dev" to setOf(stallId)
                }
                "complainant" -> {
                    val cid = p[Penalties.complaintId] ?: return null
                    val c = Complaints.selectAll().where { Complaints.id eq cid }.single()
                    val u = Users.selectAll().where { Users.id eq c[Complaints.consumerId] }.single()
                    "投诉人:${u[Users.displayName]}" to setOf(stallId)
                }
                else -> null
            }
        }

        confirmed.forEach { p ->
            val (key, stallIds) = keyOf(p) ?: return@forEach
            val pivot = p[Penalties.confirmedAt] ?: p[Penalties.createdAt]
            val existing = groups[key]
            groups[key] = if (existing == null || pivot.isBefore(existing.pivot)) {
                Group(key, stallIds, emptySet(), emptySet(), pivot)
            } else existing.copy(pivot = existing.pivot)
        }

        groups.values.map { g ->
            val before = g.pivot.minusDays(90)
            val rows = Complaints.selectAll().where { Complaints.stallId inList g.stallIds }.toList()
            fun cnt(pred: (ResultRow) -> Boolean) = rows.count { pred(it) }.toLong()
            val fines = confirmed.filter { it[Penalties.stallId] in g.stallIds }
            DimensionRow(
                key = g.key,
                complaintsBefore = cnt { it[Complaints.createdAt] >= before && it[Complaints.createdAt] < g.pivot },
                verifiedBefore = cnt { it[Complaints.createdAt] >= before && it[Complaints.createdAt] < g.pivot && it[Complaints.status] != "REJECTED" && it[Complaints.status] != "SUBMITTED" },
                complaintsAfter = cnt { it[Complaints.createdAt] >= g.pivot && it[Complaints.createdAt] < g.pivot.plusDays(90) },
                verifiedAfter = cnt { it[Complaints.createdAt] >= g.pivot && it[Complaints.createdAt] < g.pivot.plusDays(90) && it[Complaints.status] != "REJECTED" && it[Complaints.status] != "SUBMITTED" },
                penalties = fines.size.toLong(),
                fineTotal = fines.fold(BigDecimal.ZERO) { acc, r -> acc + r[Penalties.amount] }
            )
        }.sortedBy { it.key }
    }

    data class Annual(
        val year: Int, val marketName: String,
        val inspections: Long, val overError: Long,
        val complaints: Long, val verified: Long,
        val penalties: Long, val fineTotal: BigDecimal,
        val appeals: Long, val appealsAccepted: Long,
        val rectifications: Long, val rectified: Long,
        val disclosures: Long, val followUps: Long,
        val creditScore: Int, val inspectionLevel: String,
        val topStalls: List<Pair<String, Long>>
    )

    fun annual(year: Int, marketId: Int): Annual = transaction {
        val from = LocalDateTime.of(year, 1, 1, 0, 0)
        val to = from.plusYears(1)
        val market = Markets.selectAll().where { Markets.id eq marketId }.single()
        val stallIds = Stalls.selectAll().where { Stalls.marketId eq marketId }.map { it[Stalls.id] }
        val scaleIds = Scales.selectAll().where { Scales.stallId inList stallIds }.map { it[Scales.id] }
        fun <T> emptyIfNoStalls(q: () -> T, d: T): T = if (stallIds.isEmpty()) d else q()

        val inspections = emptyIfNoStalls({
            Inspections.selectAll().where { (Inspections.scaleId inList scaleIds) and (Inspections.createdAt greaterEq from) and (Inspections.createdAt less to) }.count()
        }, 0L)
        val overError = emptyIfNoStalls({
            Inspections.selectAll().where { (Inspections.scaleId inList scaleIds) and (Inspections.result eq "OVER_ERROR") and (Inspections.createdAt greaterEq from) and (Inspections.createdAt less to) }.count()
        }, 0L)
        val complaints = emptyIfNoStalls({
            Complaints.selectAll().where { (Complaints.stallId inList stallIds) and (Complaints.createdAt greaterEq from) and (Complaints.createdAt less to) }.count()
        }, 0L)
        val verified = emptyIfNoStalls({
            Complaints.selectAll().where { (Complaints.stallId inList stallIds) and (Complaints.status inList listOf("VERIFIED", "RESOLVED", "FOLLOWED_UP")) and (Complaints.createdAt greaterEq from) and (Complaints.createdAt less to) }.count()
        }, 0L)
        val penaltyRows = emptyIfNoStalls({
            Penalties.selectAll().where { (Penalties.stallId inList stallIds) and (Penalties.createdAt greaterEq from) and (Penalties.createdAt less to) }.toList()
        }, emptyList())
        val penaltyIds = penaltyRows.map { it[Penalties.id] }
        val appeals = if (penaltyIds.isEmpty()) emptyList() else Appeals.selectAll().where { Appeals.penaltyId inList penaltyIds }.toList()
        val rectifs = if (penaltyIds.isEmpty()) emptyList() else Rectifications.selectAll().where { Rectifications.penaltyId inList penaltyIds }.toList()
        val disclosures = Disclosures.selectAll().where { (Disclosures.marketId eq marketId) and (Disclosures.publishedAt greaterEq from) and (Disclosures.publishedAt less to) }.count()
        val followUps = emptyIfNoStalls({
            (FollowUps innerJoin Complaints).selectAll().where { (Complaints.stallId inList stallIds) and (FollowUps.createdAt greaterEq from) and (FollowUps.createdAt less to) }.count()
        }, 0L)
        val topStalls = emptyIfNoStalls({
            Complaints.selectAll().where { Complaints.stallId inList stallIds }.toList()
                .groupBy { it[Complaints.stallId] }
                .map { (sid, list) ->
                    val no = Stalls.selectAll().where { Stalls.id eq sid }.single()[Stalls.stallNo]
                    no to list.size.toLong()
                }.sortedByDescending { it.second }.take(5)
        }, emptyList())

        Annual(
            year, market[Markets.name], inspections, overError, complaints, verified,
            penaltyRows.size.toLong(),
            penaltyRows.fold(BigDecimal.ZERO) { a, r -> a + r[Penalties.amount] },
            appeals.size.toLong(), appeals.count { it[Appeals.status] == "ACCEPTED" }.toLong(),
            rectifs.size.toLong(), rectifs.count { it[Rectifications.status] != "SUBMITTED" }.toLong(),
            disclosures, followUps,
            market[Markets.creditScore], market[Markets.inspectionLevel], topStalls
        )
    }
}

fun BigDecimal.pctOf(base: Int): BigDecimal =
    if (base == 0) BigDecimal.ZERO else divide(BigDecimal(base), 4, RoundingMode.HALF_UP) * BigDecimal(100)
