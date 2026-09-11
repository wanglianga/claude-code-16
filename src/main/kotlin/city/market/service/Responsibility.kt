package city.market.service

import city.market.auth.Roles
import city.market.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * 多人共用秤责任认定：
 * 投诉发生后，按付款码归属、摊位排班、交易时间、商品品类、监控备注五类证据，
 * 判断早/晚市高峰共用一台秤时的实际经营者。
 * 责任明确前处罚不落到设备备案摊位；认定后拆分「设备管理责任」与「经营短斤责任」。
 */
object Responsibility {

    /** 候选摊位（备案摊位 + 共用秤排班摊位）的证据聚合 */
    data class Candidate(
        val stallId: Int, val label: String, val registered: Boolean,
        val score: Int, val evidence: List<String>
    )

    data class Assessment(
        val scaleId: Int,
        val registeredStallId: Int,
        val candidates: List<Candidate>,
        val suggestedStallId: Int?,
        val ambiguous: Boolean,
        val deviceRisk: Boolean,
        val deviceReasons: List<String>,
        val evidenceText: String
    )

    private data class TxHit(val stallId: Int, val prefix: String?, val nearby: Int)

    /** 品类关键词：商品名 → 摊位经营品类 */
    private val CATEGORY_KEYWORDS = mapOf(
        "水果" to listOf("苹果", "香蕉", "橙", "梨", "葡萄", "西瓜", "水果", "桃", "芒果"),
        "蔬菜" to listOf("白菜", "萝卜", "西红柿", "黄瓜", "土豆", "茄子", "蔬菜", "青菜", "辣椒", "番茄"),
        "水产" to listOf("鱼", "虾", "蟹", "鲈", "基围", "水产", "海鲜", "贝"),
        "肉类" to listOf("肉", "排骨", "牛肉", "猪肉", "羊肉", "肉类"),
        "禽蛋" to listOf("鸡蛋", "鸭蛋", "蛋", "鸡", "鸭", "禽"),
        "豆制品" to listOf("豆腐", "豆干", "豆皮", "千张", "豆浆", "豆")
    )

    fun slotLabel(slot: String) = if (slot == "EVENING") "晚市" else "早市"

    /** 聚合五类证据，给出实际经营者建议分 */
    fun assess(complaintId: Int, monitorNote: String? = null): Assessment = transaction {
        val c = Complaints.selectAll().where { Complaints.id eq complaintId }.single()
        val scaleId = c[Complaints.scaleId] ?: error("该投诉未匹配到电子秤")
        val scale = Scales.selectAll().where { Scales.id eq scaleId }.single()
        val registeredStallId = scale[Scales.stallId]
        val purchase = c[Complaints.purchaseTime]
        val hour = purchase.hour

        // 候选摊位：备案摊位 + 排班出现的相邻摊位
        val scheduleRows = ScaleSchedules.selectAll().where { ScaleSchedules.scaleId eq scaleId }.toList()
        val candidateIds = (listOf(registeredStallId) + scheduleRows.map { it[ScaleSchedules.stallId] }).distinct()
        val stalls = Stalls.selectAll().where { Stalls.id inList candidateIds }.associateBy { it[Stalls.id] }
        val markets = Markets.selectAll().associate { it[Markets.id] to it[Markets.name] }
        val vendors = Users.selectAll().where { Users.role eq Roles.VENDOR }.associate { it[Users.id] to it[Users.displayName] }
        val payCodes = PaymentCodes.selectAll().where { PaymentCodes.stallId inList candidateIds }.toList()

        fun stallLabel(sid: Int): String {
            val st = stalls.getValue(sid)
            val vendor = st[Stalls.vendorId]?.let { vendors[it] } ?: "未分配摊主"
            return "${markets[st[Stalls.marketId]]} ${st[Stalls.stallNo]}（${st[Stalls.category]}，$vendor）"
        }

        // 证据 1：付款码前缀归属
        val payRef = c[Complaints.paymentRef]
        val payHit = payRef?.let { ref ->
            payCodes.filter { ref.startsWith(it[PaymentCodes.prefix]) }
                .map { it[PaymentCodes.stallId] to it[PaymentCodes.prefix] }
                .distinctBy { it.first }
        } ?: emptyList()

        // 证据 2：排班命中（购买时间落在谁的班次）
        val scheduleHit = scheduleRows.filter { it[ScaleSchedules.startHour] <= hour && hour < it[ScaleSchedules.endHour] }
            .map { it[ScaleSchedules.stallId] to "${slotLabel(it[ScaleSchedules.slot])} ${it[ScaleSchedules.startHour]}:00-${it[ScaleSchedules.endHour]}:00" }

        // 证据 3：交易流水归属（同秤临近时段、同重量区间的流水记在哪个摊位/收款渠道）
        val txHits = run {
            // 同秤前后 90 分钟、重量 ±15% 的流水，按摊位聚合
            val rows = Transactions.selectAll().where {
                (Transactions.scaleId eq scaleId) and
                    (Transactions.ts greaterEq purchase.minusMinutes(90)) and
                    (Transactions.ts lessEq purchase.plusMinutes(90)) and
                    (Transactions.weightG greaterEq (c[Complaints.nominalWeightG] * 0.85).toInt()) and
                    (Transactions.weightG lessEq (c[Complaints.nominalWeightG] * 1.15).toInt())
            }.toList()
            rows.groupBy { it[Transactions.stallId] }.map { (sid, list) ->
                TxHit(sid, list.firstOrNull { it[Transactions.paymentPrefix] != null }?.get(Transactions.paymentPrefix), list.size)
            }.sortedByDescending { it.nearby }
        }

        // 证据 4：商品品类
        val productCategory = CATEGORY_KEYWORDS.entries.firstOrNull { (_, kws) ->
            kws.any { c[Complaints.product].contains(it) }
        }?.key

        // 证据 5：监控备注（提及摊位号 / 摊主姓名 / 品类）
        val note = monitorNote?.trim().orEmpty()
        fun monitorMention(sid: Int): String? {
            val st = stalls.getValue(sid)
            val no = st[Stalls.stallNo]
            val vendor = st[Stalls.vendorId]?.let { vendors[it] }
            val hit = no in note || (vendor != null && vendor.takeLast(3) in note) ||
                productCategory != null && productCategory == st[Stalls.category] && productCategory in note
            return if (hit) note.ifBlank { null } else null
        }

        val candidates = candidateIds.map { sid ->
            val ev = mutableListOf<String>()
            var score = 0
            payHit.firstOrNull { it.first == sid }?.let { (_, prefix) ->
                score += 40; ev += "付款码：付款单号 ${payRef} 归属该摊位收款码前缀 $prefix（+40）"
            }
            scheduleHit.firstOrNull { it.first == sid }?.let { (_, slot) ->
                score += 20; ev += "摊位排班：购买时段 ${hour}:xx 落在该摊位班次（$slot）（+20）"
            }
            txHits.firstOrNull { it.stallId == sid }?.let { h ->
                score += 30
                ev += "交易流水：同秤临近时段匹配 ${h.nearby} 笔流水记在该摊位" +
                    (h.prefix?.let { p -> "，收款渠道 $p" } ?: "") + "（+30）"
            }
            if (productCategory != null && stalls.getValue(sid)[Stalls.category] == productCategory) {
                score += 15; ev += "商品品类：${c[Complaints.product]}属「$productCategory」，与该摊位经营品类一致（+15）"
            }
            monitorMention(sid)?.let {
                score += 25; ev += "监控备注：备注内容指向该摊位（+25）"
            }
            if (sid == registeredStallId) ev += "设备备案摊位（责任明确前不直接落处罚）"
            Candidate(sid, stallLabel(sid), sid == registeredStallId, score, ev)
        }.sortedByDescending { it.score }

        val best = candidates.firstOrNull { it.score > 0 }
        val second = candidates.drop(1).firstOrNull { it.score > 0 }
        // 第一名证据不足或与第二名平分 → 存疑，需人工判定
        val ambiguous = best == null || best.score < 20 || (second != null && second.score >= best.score)
        val suggested = if (!ambiguous) best!!.stallId else null

        // 设备管理风险提示（证书过期 / 停用 / 近期超标 / 封签破损）
        val deviceReasons = mutableListOf<String>()
        if (purchase.toLocalDate().isAfter(scale[Scales.certValidUntil])) deviceReasons += "交易时检定证书已过期"
        if (scale[Scales.status] == "SUSPENDED") deviceReasons += "设备处于停用状态"
        val recentOver = Inspections.selectAll().where {
            (Inspections.scaleId eq scaleId) and (Inspections.result eq "OVER_ERROR") and
                (Inspections.createdAt lessEq purchase) and (Inspections.createdAt greaterEq purchase.minusDays(90))
        }.any()
        if (recentOver) deviceReasons += "近 90 天该秤有误差超标记录"
        val brokenSeal = Inspections.selectAll().where {
            (Inspections.scaleId eq scaleId) and (Inspections.sealStatus eq "BROKEN") and
                (Inspections.createdAt lessEq purchase)
        }.any()
        if (brokenSeal) deviceReasons += "近期检查封签破损"

        val evidenceText = buildString {
            append("投诉商品：${c[Complaints.product]}，购买时间 ${purchase.toString().replace('T', ' ')}")
            appendLine()
            candidates.forEach { cand ->
                appendLine("● ${cand.label}  证据分 ${cand.score}")
                if (cand.evidence.isEmpty()) appendLine("  无指向性证据")
                cand.evidence.forEach { appendLine("  - $it") }
            }
            if (note.isNotBlank()) appendLine("监控备注：$note")
            appendLine(if (ambiguous) "引擎结论：证据不足或存在冲突，实际经营者存疑，需监管人工判定。"
            else "引擎建议：实际经营者为 ${candidates.first { it.stallId == suggested }.label}。")
        }

        Assessment(scaleId, registeredStallId, candidates, suggested, ambiguous, deviceReasons.isNotEmpty(), deviceReasons, evidenceText)
    }

    /**
     * 共用秤投诉核验入口：责任明确前不开处罚、不落备案摊位，
     * 生成（或返回已有的）待认定责任单，投诉置「责任认定中」。
     */
    fun openCase(complaintId: Int, monitorNote: String? = null): Int = transaction {
        val existing = RespCases.selectAll().where { RespCases.complaintId eq complaintId }
            .orderBy(RespCases.id, SortOrder.DESC).limit(1).singleOrNull()
        if (existing != null && existing[RespCases.status] == "PENDING") {
            return@transaction existing[RespCases.id]
        }
        val a = assess(complaintId, monitorNote)
        val now = LocalDateTime.now()
        val caseId = RespCases.insert {
            it[RespCases.complaintId] = complaintId
            it[RespCases.scaleId] = a.scaleId
            it[RespCases.registeredStallId] = a.registeredStallId
            it[RespCases.suggestedStallId] = a.suggestedStallId
            it[RespCases.evidence] = a.evidenceText
            it[RespCases.monitorNote] = monitorNote
            it[RespCases.status] = "PENDING"
            it[RespCases.createdAt] = now
        } get RespCases.id
        Complaints.update({ Complaints.id eq complaintId }) {
            it[Complaints.status] = "RESP_PENDING"; it[Complaints.respCaseId] = caseId
        }
        Db.logEvent(a.scaleId, "RESP_PENDING", "共用秤投诉 #$complaintId 责任认定中：处罚暂不落到备案摊位，待按付款码/排班/时间/品类/监控认定实际经营者")
        caseId
    }

    /**
     * 监管认定实际经营者并拆分处罚：
     * - 经营短斤责任（OPERATION）→ 实际经营摊位
     * - 设备管理责任（DEVICE，可选）→ 设备备案摊位
     * 责任明确前处罚不会落到备案摊位；认定后两类责任分别记录、同组关联。
     * 返回(经营处罚id, 设备处罚id?)。
     */
    fun decide(
        caseId: Int, regulatorId: Int, operatorStallId: Int,
        deviceFault: Boolean, monitorNote: String?
    ): Pair<Int, Int?> = transaction {
        val cs = RespCases.selectAll().where { RespCases.id eq caseId }.single()
        require(cs[RespCases.status] == "PENDING") { "该责任单已认定" }
        val complaintId = cs[RespCases.complaintId]
        val c = Complaints.selectAll().where { Complaints.id eq complaintId }.single()
        val scaleId = cs[RespCases.scaleId]
        val registeredStallId = cs[RespCases.registeredStallId]
        val now = LocalDateTime.now()
        val group = "RSP-$caseId"
        val shortfall = c[Complaints.shortfallG] ?: 0
        val amount = java.math.BigDecimal(if (shortfall >= 50) 500 else 200)

        Complaints.update({ Complaints.id eq complaintId }) {
            it[Complaints.status] = "VERIFIED"; it[Complaints.operatorStallId] = operatorStallId
        }

        // 经营短斤责任 → 实际经营摊位（与备案摊位不同也正确归集）
        val opReason = "投诉核验属实（共用秤责任认定）：${c[Complaints.product]} 标称 ${c[Complaints.nominalWeightG]}g " +
            "复称 ${c[Complaints.reweighedWeightG]}g，短少 $shortfall g；实际经营者经付款码/排班/交易时间/品类/监控认定"
        val opPid = Penalties.insert {
            it[Penalties.stallId] = operatorStallId; it[Penalties.scaleId] = scaleId
            it[Penalties.complaintId] = complaintId
            it[Penalties.amount] = amount; it[Penalties.reason] = opReason
            it[Penalties.kind] = "OPERATION"; it[Penalties.splitGroup] = group
            it[Penalties.status] = "ISSUED"; it[Penalties.issuedBy] = regulatorId; it[Penalties.createdAt] = now
        } get Penalties.id

        // 设备管理责任 → 备案摊位（仅在认定存在封签/检定/维护问题时）
        var devPid: Int? = null
        if (deviceFault) {
            val devReason = "设备管理责任（共用秤投诉 #$complaintId 拆分认定）：备案摊位对电子秤封签、检定或维护管理不到位" +
                if (operatorStallId != registeredStallId) "；该笔短斤交易实际经营者为相邻摊位，设备管理与经营行为分别记录" else ""
            devPid = Penalties.insert {
                it[Penalties.stallId] = registeredStallId; it[Penalties.scaleId] = scaleId
                it[Penalties.complaintId] = null
                it[Penalties.amount] = java.math.BigDecimal(300)
                it[Penalties.reason] = devReason
                it[Penalties.kind] = "DEVICE"; it[Penalties.operatorStallId] = operatorStallId
                it[Penalties.splitGroup] = group
                it[Penalties.status] = "ISSUED"; it[Penalties.issuedBy] = regulatorId; it[Penalties.createdAt] = now
            } get Penalties.id
        }

        val refreshedEvidence = if (!monitorNote.isNullOrBlank()) assess(complaintId, monitorNote).evidenceText else null
        RespCases.update({ RespCases.id eq caseId }) {
            it[RespCases.status] = "CONFIRMED"; it[RespCases.decidedStallId] = operatorStallId
            it[RespCases.monitorNote] = monitorNote
            it[RespCases.deviceFault] = deviceFault
            it[RespCases.decidedBy] = regulatorId; it[RespCases.decidedAt] = now
            if (refreshedEvidence != null) it[RespCases.evidence] = refreshedEvidence
        }
        Db.logEvent(
            scaleId, "RESP_SPLIT",
            "投诉 #$complaintId 责任认定完成：经营短斤责任 → 处罚 #$opPid（摊位 #$operatorStallId）" +
                (devPid?.let { "；设备管理责任 → 处罚 #$it（备案摊位 #$registeredStallId）" } ?: "")
        )
        opPid to devPid
    }

    /** 驳回：责任单撤销，投诉驳回 */
    fun reject(caseId: Int) = transaction {
        val cs = RespCases.selectAll().where { RespCases.id eq caseId }.single()
        RespCases.update({ RespCases.id eq caseId }) { it[status] = "CANCELLED" }
        Complaints.update({ Complaints.id eq cs[RespCases.complaintId] }) { it[status] = "REJECTED" }
        Db.logEvent(cs[RespCases.scaleId], "RESP_PENDING", "共用秤投诉 #${cs[RespCases.complaintId]} 责任认定驳回，投诉不成立")
    }

    /** 该秤是否为多人共用（标记位或存在多个摊位排班） */
    fun isSharedScale(scaleId: Int): Boolean = transaction {
        val s = Scales.selectAll().where { Scales.id eq scaleId }.single()
        s[Scales.shared] || ScaleSchedules.selectAll().where { ScaleSchedules.scaleId eq scaleId }
            .map { it[ScaleSchedules.stallId] }.distinct().size > 1
    }
}
