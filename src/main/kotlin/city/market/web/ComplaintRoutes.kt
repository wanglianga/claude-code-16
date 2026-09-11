package city.market.web

import city.market.auth.Roles
import city.market.db.*
import city.market.service.Trust
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun Route.complaintRoutes() {
    route("/consumer") {

        get("/complaints") {
            val s = call.requireRole(Roles.CONSUMER) ?: return@get
            val rows = transaction {
                Complaints.join(Stalls, JoinType.INNER, Complaints.stallId, Stalls.id)
                    .join(Markets, JoinType.INNER, Stalls.marketId, Markets.id)
                    .selectAll()
                    .where { Complaints.consumerId eq s.userId }
                    .orderBy(Complaints.createdAt, SortOrder.DESC).map {
                        ComplaintRow(
                            it[Complaints.id], it[Markets.name], it[Stalls.stallNo], it[Complaints.product],
                            it[Complaints.purchaseTime].toString(), it[Complaints.nominalWeightG],
                            it[Complaints.reweighedWeightG], it[Complaints.shortfallG],
                            it[Complaints.status], it[Complaints.trustFlags]
                        )
                    }
            }
            call.respondHtml {
                page("我的投诉", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"我的投诉" }
                        p { a(href = "/consumer/complaints/new", classes = "btn") { +"＋ 发起短斤少两投诉" } }
                        if (rows.isEmpty()) p("muted") { +"暂无投诉记录" }
                        rows.forEach { r ->
                            div("card") {
                                h2 { +"#${r.id} ${r.product}（${r.market} ${r.stall}）" }
                                p {
                                    +"购买时间：${r.time.take(16)} ｜ 标称 ${r.nominal}g ｜ 复称 ${r.reweighed}g ｜ 短少 ${r.shortfall ?: "-"}g "
                                    badge(Labels.complaint(r.status), statusBadgeClass(r.status))
                                }
                                if (!r.flags.isNullOrBlank()) {
                                    p {
                                        +"交易可信度提示："
                                        r.flags.split(",").filter { it.isNotBlank() }.forEach {
                                            badge(Trust.flagLabel(it), "b-yellow")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        get("/complaints/new") {
            val s = call.requireRole(Roles.CONSUMER) ?: return@get
            val stalls = transaction {
                (Stalls innerJoin Markets).selectAll().orderBy(Stalls.id)
                    .map { it[Stalls.id] to "${it[Markets.name]} · ${it[Stalls.stallNo]}（${it[Stalls.category]}）" }
            }
            call.respondHtml {
                page("发起投诉", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"短斤少两投诉" }
                        form(method = FormMethod.post, action = "/consumer/complaints/new", encType = FormEncType.multipartFormData) {
                            label { +"购买摊位" }
                            select { name = "stallId"; stalls.forEach { (id, l) -> option { value = "$id"; +l } } }
                            label { +"购买时间" }; dateTimeLocalInput { name = "purchaseTime"; required = true }
                            label { +"商品名称" }; textInput { name = "product"; required = true }
                            label { +"标称重量（克）" }; numberInput { name = "nominalWeightG"; required = true }
                            label { +"复称重量（克）" }; numberInput { name = "reweighedWeightG"; required = true }
                            label { +"付款记录单号" }; textInput { name = "paymentRef" }
                            label { +"现场照片" }; fileInput { name = "photo" }
                            button(classes = "btn", type = ButtonType.submit) { +"提交投诉" }
                        }
                    }
                }
            }
        }

        post("/complaints/new") {
            val s = call.requireRole(Roles.CONSUMER) ?: return@post
            var stallId = 0; var purchaseTime = ""; var product = ""
            var nominal = 0; var reweighed = 0; var paymentRef: String? = null; var photo: String? = null
            val parts = mutableListOf<PartData>()
            val multipart = call.receiveMultipart()
            while (true) { val p = multipart.readPart() ?: break; parts += p }
            parts.forEach { part ->
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "stallId" -> stallId = part.value.toIntOrNull() ?: 0
                        "purchaseTime" -> purchaseTime = part.value
                        "product" -> product = part.value
                        "nominalWeightG" -> nominal = part.value.toIntOrNull() ?: 0
                        "reweighedWeightG" -> reweighed = part.value.toIntOrNull() ?: 0
                        "paymentRef" -> paymentRef = part.value.ifBlank { null }
                    }
                    is PartData.FileItem -> if (part.name == "photo" && !part.originalFileName.isNullOrBlank()) {
                        val ext = part.originalFileName!!.substringAfterLast('.', "jpg").replace(Regex("[^a-zA-Z0-9]"), "")
                        val name = "${java.util.UUID.randomUUID()}.$ext"
                        java.io.File(Db.uploadDir, name).writeBytes(part.provider().readBytes())
                        photo = name
                    }
                    else -> {}
                }
                part.dispose()
            }
            if (stallId == 0 || purchaseTime.isBlank() || nominal <= 0 || reweighed <= 0) {
                call.respondRedirect("/consumer/complaints/new?msg=" + enc("请完整填写必填项"))
                return@post
            }
            val pt = LocalDateTime.parse(purchaseTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val a = Trust.assess(stallId, pt, nominal, reweighed)
            val id = transaction {
                Complaints.insert {
                    it[consumerId] = s.userId; it[Complaints.stallId] = stallId
                    it[scaleId] = a.scaleId; it[Complaints.purchaseTime] = pt
                    it[Complaints.product] = product
                    it[nominalWeightG] = nominal; it[reweighedWeightG] = reweighed
                    it[Complaints.paymentRef] = paymentRef; it[photos] = photo
                    it[trustFlags] = a.flags.joinToString(",")
                    it[matchedTxId] = a.matchedTxId; it[shortfallG] = a.shortfallG
                    it[createdAt] = LocalDateTime.now()
                } get Complaints.id
            }
            val msg = buildString {
                append("投诉已提交（#$id）。")
                if (a.suspected) append("复称短少 ${a.shortfallG}g，疑似短斤少两，已转监管核验。")
                else append("短少量未超判定阈值，监管将结合交易记录复核。")
                if (!a.trusted) append("注意：该笔交易存在可信度风险（${a.flags.joinToString("、") { Trust.flagLabel(it) }}）。")
            }
            call.respondRedirect("/consumer/complaints?msg=" + enc(msg))
        }
    }
}

data class ComplaintRow(
    val id: Int, val market: String, val stall: String, val product: String, val time: String,
    val nominal: Int, val reweighed: Int, val shortfall: Int?, val status: String, val flags: String?
)
