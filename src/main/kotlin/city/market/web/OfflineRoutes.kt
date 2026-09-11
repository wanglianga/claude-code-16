package city.market.web

import city.market.auth.Roles
import city.market.auth.UserSession
import city.market.db.*
import city.market.service.OfflineSync
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun Route.offlineRoutes() {

    // ---------- 补传表单（市场管理方 / 本摊位摊主） ----------
    get("/scales/{id}/offline-sync") {
        val s = call.requireRole(Roles.MARKET_ADMIN, Roles.VENDOR) ?: return@get
        val id = call.parameters["id"]!!.toInt()
        val scale = loadScaleForSync(id, s) ?: run {
            call.respondRedirect("/?msg=" + enc("无权访问该设备")); return@get
        }
        val now = LocalDateTime.now()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        call.respondHtml {
            page("离线交易补传", s) {
                msgBox(call.request.queryParameters["msg"])
                div("card") {
                    h1 { +"离线交易补传 · 设备 ${scale[Scales.deviceNo]}" }
                    p("muted") {
                        +"恢复网络时，服务将比对：本地流水与服务器已有流水（查重）、设备时钟与服务器时间（漂移）、摊位绑定（离线期间是否换绑）、封签状态；"
                        +"离线窗口内如有投诉，相关交易进入人工复核；离线时间异常集中在高峰期将生成重点巡检任务并扣减摊位信用。"
                    }
                    form(method = FormMethod.post, action = "/scales/$id/offline-sync") {
                        label { +"离线开始时间" }; dateTimeLocalInput { name = "offlineStart"; required = true }
                        label { +"恢复网络时间" }; dateTimeLocalInput { name = "offlineEnd"; value = now.format(fmt); required = true }
                        label { +"设备本地时钟读数（用于与服务器时间比对）" }; dateTimeLocalInput { name = "deviceClock"; value = now.format(fmt); required = true }
                        label { +"补传时封签状态" }
                        select {
                            name = "sealAtSync"
                            option { value = "INTACT"; +"完好" }
                            option { value = "BROKEN"; +"破损" }
                        }
                        label { +"本地流水 CSV（每行：yyyy-MM-dd HH:mm,重量克,金额）" }
                        textArea {
                            name = "csv"; rows = "8"
                            placeholder = "2026-09-10 07:15,1250,45.00\n2026-09-10 07:48,860,30.96"
                        }
                        button(classes = "btn", type = ButtonType.submit) { +"上传并补传" }
                    }
                }
            }
        }
    }

    post("/scales/{id}/offline-sync") {
        val s = call.requireRole(Roles.MARKET_ADMIN, Roles.VENDOR) ?: return@post
        val id = call.parameters["id"]!!.toInt()
        loadScaleForSync(id, s) ?: run { call.respondRedirect("/?msg=" + enc("无权访问该设备")); return@post }
        val p = call.receiveParameters()
        fun dt(k: String) = p[k]?.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
        val start = dt("offlineStart"); val end = dt("offlineEnd"); val clock = dt("deviceClock")
        val csv = p["csv"] ?: ""
        if (start == null || end == null) {
            call.respondRedirect("/scales/$id/offline-sync?msg=" + enc("请填写离线窗口时间")); return@post
        }
        val result = try {
            OfflineSync.process(id, s.userId, start, end, clock, p["sealAtSync"] ?: "INTACT", csv)
        } catch (e: IllegalArgumentException) {
            call.respondRedirect("/scales/$id/offline-sync?msg=" + enc(e.message ?: "参数错误")); return@post
        }
        call.respondRedirect("/offline-syncs/${result.batchId}")
    }

    // ---------- 批次详情（管理方 / 本摊位摊主 / 监管） ----------
    get("/offline-syncs/{id}") {
        val s = call.requireRole(Roles.MARKET_ADMIN, Roles.VENDOR, Roles.REGULATOR) ?: return@get
        val id = call.parameters["id"]!!.toInt()
        val batch = transaction {
            OfflineSyncs.selectAll().where { OfflineSyncs.id eq id }.singleOrNull()
        } ?: run { call.respondRedirect("/"); return@get }
        if (s.role == Roles.VENDOR) {
            val owner = transaction {
                Stalls.selectAll().where { Stalls.id eq batch[OfflineSyncs.stallId] }.single()[Stalls.vendorId]
            }
            if (owner != s.userId) { call.respondRedirect("/vendor/home?msg=" + enc("无权查看")); return@get }
        }
        val scaleNo = transaction { Scales.selectAll().where { Scales.id eq batch[OfflineSyncs.scaleId] }.single()[Scales.deviceNo] }
        val txs = transaction {
            Transactions.selectAll().where {
                (Transactions.scaleId eq batch[OfflineSyncs.scaleId]) and
                    (Transactions.ts greaterEq batch[OfflineSyncs.offlineStart]) and
                    (Transactions.ts lessEq batch[OfflineSyncs.offlineEnd])
            }.orderBy(Transactions.ts).limit(200).toList()
        }
        val complaints = transaction {
            Complaints.selectAll().where {
                (Complaints.stallId eq batch[OfflineSyncs.stallId]) and
                    (Complaints.purchaseTime greaterEq batch[OfflineSyncs.offlineStart]) and
                    (Complaints.purchaseTime lessEq batch[OfflineSyncs.offlineEnd])
            }.toList()
        }
        val anomalies = batch[OfflineSyncs.anomalies].split(",").filter { it.isNotBlank() }
        call.respondHtml {
            page("补传批次详情", s) {
                div("card") {
                    h1 { +"补传批次 #$id · 设备 $scaleNo" }
                    p {
                        +"状态："; batchStatusBadge(batch[OfflineSyncs.status])
                        +" 离线窗口：${batch[OfflineSyncs.offlineStart].toString().replace('T', ' ')} ~ ${batch[OfflineSyncs.offlineEnd].toString().replace('T', ' ')}"
                    }
                    if (anomalies.isNotEmpty()) {
                        p { +"异常标记："; anomalies.forEach { badge(OfflineSync.anomalyLabel(it), "b-red") } }
                    }
                    if (batch[OfflineSyncs.needReinspection]) {
                        p { badge("已建议补做抽检（见抽检任务）", "b-yellow") }
                    }
                }
                div("card") {
                    h2 { +"一致性比对结果" }
                    table {
                        tr { th { +"比对项" }; th { +"结果" } }
                        tr { td { +"本地流水 vs 服务器" }; td { +"上传 ${batch[OfflineSyncs.txUploaded]} 笔，入库 ${batch[OfflineSyncs.txAccepted]} 笔，重复 ${batch[OfflineSyncs.txDuplicate]} 笔，越界 ${batch[OfflineSyncs.txInvalid]} 笔" } }
                        tr { td { +"设备时钟 vs 服务器" }; td { +"漂移 ${batch[OfflineSyncs.clockDriftMin]} 分钟"; if (batch[OfflineSyncs.clockDriftMin] > 5) badge("异常", "b-red") else badge("正常", "b-green") } }
                        tr { td { +"摊位绑定" }; td { if ("BINDING_CHANGED" in anomalies) { badge("离线期间发生换绑", "b-red") } else badge("绑定一致", "b-green") } }
                        tr { td { +"封签状态" }; td { badge(Labels.seal(batch[OfflineSyncs.sealAtSync]), if (batch[OfflineSyncs.sealAtSync] == "INTACT") "b-green" else "b-red") } }
                        tr { td { +"高峰期占比" }; td { +"${batch[OfflineSyncs.peakRatio]}%"; if ("PEAK_CONCENTRATED" in anomalies) badge("异常集中，已生成重点巡检并扣减信用", "b-red") } }
                        tr { td { +"离线期间投诉" }; td { +"${batch[OfflineSyncs.complaintsInWindow]} 起"; if (batch[OfflineSyncs.complaintsInWindow] > 0) badge("相关交易已进入人工复核", "b-yellow") } }
                    }
                }
                div("card") {
                    h2 { +"离线窗口交易可信度（已重算，共 ${batch[OfflineSyncs.txReevaluated]} 笔）" }
                    table {
                        tr { th { +"时间" }; th { +"重量(g)" }; th { +"金额" }; th { +"可信度" }; th { +"标记" } }
                        txs.forEach { tx ->
                            tr {
                                td { +tx[Transactions.ts].toString().replace('T', ' ') }
                                td { +"${tx[Transactions.weightG]}" }
                                td { +"¥${tx[Transactions.amountYuan]}" }
                                td { trustBadge(tx[Transactions.trust]) }
                                td {
                                    (tx[Transactions.trustFlags] ?: "").split(",").filter { it.isNotBlank() }
                                        .forEach { badge(OfflineSync.anomalyLabel(it).ifBlank { it }, "b-yellow") }
                                }
                            }
                        }
                    }
                }
                if (complaints.isNotEmpty()) {
                    div("card") {
                        h2 { +"离线期间投诉（人工复核关联）" }
                        complaints.forEach { c ->
                            p { +"#${c[Complaints.id]} ${c[Complaints.product]} 标称 ${c[Complaints.nominalWeightG]}g 复称 ${c[Complaints.reweighedWeightG]}g ｜ "; badge(Labels.complaint(c[Complaints.status]), statusBadgeClass(c[Complaints.status])) }
                        }
                    }
                }
            }
        }
    }

    // ---------- 监管：人工复核队列 ----------
    get("/reg/offline") {
        val s = call.requireRole(Roles.REGULATOR) ?: return@get
        val rows = transaction {
            OfflineSyncs
                .join(Scales, JoinType.INNER, OfflineSyncs.scaleId, Scales.id)
                .join(Stalls, JoinType.INNER, OfflineSyncs.stallId, Stalls.id)
                .selectAll()
                .orderBy(OfflineSyncs.createdAt, SortOrder.DESC).limit(100).toList()
        }
        call.respondHtml {
            page("离线补传复核", s) {
                msgBox(call.request.queryParameters["msg"])
                div("card") {
                    h1 { +"离线交易补传 · 人工复核" }
                    if (rows.isEmpty()) p("muted") { +"暂无补传批次" }
                    rows.forEach { r ->
                        val b = r[OfflineSyncs.id]
                        val anomalies = r[OfflineSyncs.anomalies].split(",").filter { it.isNotBlank() }
                        div("card") {
                            h2 {
                                +"批次 #$b · ${r[Scales.deviceNo]}（${r[Stalls.stallNo]}） "
                                batchStatusBadge(r[OfflineSyncs.status])
                            }
                            p {
                                +"窗口 ${r[OfflineSyncs.offlineStart].toString().replace('T', ' ')} ~ ${r[OfflineSyncs.offlineEnd].toString().replace('T', ' ')} ｜ "
                                +"入库 ${r[OfflineSyncs.txAccepted]}/${r[OfflineSyncs.txUploaded]} 笔 ｜ 高峰占比 ${r[OfflineSyncs.peakRatio]}% ｜ 待复核 ${r[OfflineSyncs.pendingReview]} 笔 ｜ 投诉 ${r[OfflineSyncs.complaintsInWindow]} 起"
                            }
                            if (anomalies.isNotEmpty()) p { anomalies.forEach { badge(OfflineSync.anomalyLabel(it), "b-red") } }
                            p {
                                a(href = "/offline-syncs/$b", classes = "btn sm gray") { +"详情" }
                                if (r[OfflineSyncs.status] == "SYNCED" && r[OfflineSyncs.pendingReview] > 0) {
                                    form(method = FormMethod.post, action = "/reg/offline/$b/review", classes = "inline") {
                                        hiddenInput { name = "decision"; value = "OK" }
                                        button(classes = "btn sm", type = ButtonType.submit) { +"复核通过" }
                                    }
                                    form(method = FormMethod.post, action = "/reg/offline/$b/review", classes = "inline") {
                                        hiddenInput { name = "decision"; value = "FLAG" }
                                        button(classes = "btn sm red", type = ButtonType.submit) { +"标记可疑" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    post("/reg/offline/{id}/review") {
        call.requireRole(Roles.REGULATOR) ?: return@post
        val id = call.parameters["id"]!!.toInt()
        val decision = call.receiveParameters()["decision"] ?: "OK"
        OfflineSync.review(id, decision == "OK")
        call.respondRedirect("/reg/offline?msg=" + enc("批次 #$id 复核完成"))
    }
}

private fun loadScaleForSync(scaleId: Int, s: UserSession): ResultRow? = transaction {
    val row = Scales.selectAll().where { Scales.id eq scaleId }.singleOrNull() ?: return@transaction null
    if (s.role == Roles.VENDOR) {
        val owner = Stalls.selectAll().where { Stalls.id eq row[Scales.stallId] }.single()[Stalls.vendorId]
        if (owner != s.userId) return@transaction null
    }
    row
}

private fun FlowContent.batchStatusBadge(status: String) = when (status) {
    "REVIEWED" -> badge("已复核", "b-green")
    "FLAGGED" -> badge("可疑", "b-red")
    else -> badge("待复核", "b-yellow")
}

private fun FlowContent.trustBadge(trust: String) = when (trust) {
    "TRUSTED" -> badge("补传可信", "b-green")
    "PENDING_REVIEW" -> badge("待人工复核", "b-yellow")
    "REVIEWED_OK" -> badge("复核通过", "b-green")
    "SUSPICIOUS" -> badge("可疑", "b-red")
    else -> badge("在线正常", "")
}
