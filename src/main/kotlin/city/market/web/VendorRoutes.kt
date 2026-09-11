package city.market.web

import city.market.auth.Roles
import city.market.db.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

fun Route.vendorRoutes() {
    route("/vendor") {

        get("/home") {
            val s = call.requireRole(Roles.VENDOR) ?: return@get
            val stalls = transaction {
                (Stalls innerJoin Markets).selectAll().where { Stalls.vendorId eq s.userId }.map {
                    Triple(it[Stalls.id], it[Markets.name], it[Stalls.stallNo])
                }
            }
            val stallIds = stalls.map { it.first }
            val scales = transaction {
                if (stallIds.isEmpty()) emptyList() else
                    Scales.selectAll().where { Scales.stallId inList stallIds }.map {
                        ScaleRow(
                            it[Scales.id], it[Scales.deviceNo], "", it[Scales.stallId].toString(),
                            it[Scales.category], it[Scales.certNo], it[Scales.certValidUntil].toString(),
                            it[Scales.online], it[Scales.status], it[Scales.shared], it[Scales.sealPhoto]
                        )
                    }
            }
            val inspections = transaction {
                if (stallIds.isEmpty()) emptyList() else
                    (Inspections innerJoin Scales).selectAll().where { Scales.stallId inList stallIds }
                        .orderBy(Inspections.createdAt, SortOrder.DESC).limit(20).map {
                            VendorInspection(
                                it[Inspections.id], it[Scales.deviceNo], it[Inspections.createdAt].toString(),
                                it[Inspections.standardWeightG], it[Inspections.displayedWeightG],
                                it[Inspections.errorPct].toPlainString(), it[Inspections.result],
                                it[Inspections.vendorConfirmed]
                            )
                        }
            }
            val penalties = transaction {
                if (stallIds.isEmpty()) emptyList() else
                    Penalties.selectAll().where { Penalties.stallId inList stallIds }
                        .orderBy(Penalties.createdAt, SortOrder.DESC).limit(50).map {
                            VendorPenalty(
                                it[Penalties.id], it[Penalties.reason], it[Penalties.amount].toPlainString(),
                                it[Penalties.status], it[Penalties.createdAt].toString()
                            )
                        }
            }
            val appealed = transaction {
                Appeals.selectAll().where { Appeals.vendorId eq s.userId }.map { it[Appeals.penaltyId] }.toSet()
            }
            val rectified = transaction {
                if (stallIds.isEmpty()) emptySet() else
                    Rectifications.selectAll().where { Rectifications.stallId inList stallIds }
                        .map { it[Rectifications.penaltyId] }.toSet()
            }
            call.respondHtml {
                page("我的摊位", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"我的摊位与电子秤" }
                        stalls.forEach { (_, market, no) -> p { +"摊位：$market $no" } }
                        table {
                            tr { th { +"设备编号" }; th { +"证书有效期" }; th { +"联网" }; th { +"状态" }; th { +"档案" } }
                            scales.forEach { r ->
                                tr {
                                    td { +r.deviceNo }; td { +r.certUntil }
                                    td { badge(if (r.online) "在线" else "离线", if (r.online) "b-green" else "b-red") }
                                    td { badge(Labels.scale(r.status), statusBadgeClass(r.status)) }
                                    td {
                                        a(href = "/reg/scales/${r.id}") { +"监管档案" }
                                        +" "
                                        a(href = "/scales/${r.id}/offline-sync") { +"离线补传" }
                                    }
                                }
                            }
                        }
                    }
                    div("card") {
                        h2 { +"抽检记录（请确认）" }
                        table {
                            tr { th { +"时间" }; th { +"设备" }; th { +"标准/显示(g)" }; th { +"误差%" }; th { +"结果" }; th { +"确认" } }
                            inspections.forEach { r ->
                                tr {
                                    td { +r.time.take(16) }; td { +r.device }
                                    td { +"${r.standard} / ${r.displayed}" }; td { +r.errorPct }
                                    td { badge(Labels.result(r.result), if (r.result == "PASS") "b-green" else "b-red") }
                                    td {
                                        if (r.confirmed) +"已确认" else form(
                                            method = FormMethod.post,
                                            action = "/vendor/inspections/${r.id}/confirm", classes = "inline"
                                        ) { button(classes = "btn sm", type = ButtonType.submit) { +"现场确认" } }
                                    }
                                }
                            }
                        }
                    }
                    div("card") {
                        h2 { +"我的处罚" }
                        if (penalties.isEmpty()) p("muted") { +"暂无处罚记录" }
                        penalties.forEach { p ->
                            div("card") {
                                p {
                                    +"#${p.id} ｜ ${p.reason} ｜ 罚款 ¥${p.amount} ｜ "
                                    badge(Labels.penalty(p.status), statusBadgeClass(p.status))
                                    +" ｜ ${p.time.take(16)}"
                                }
                                if (p.status == "ISSUED" && p.id !in appealed) {
                                    form(method = FormMethod.post, action = "/vendor/penalties/${p.id}/appeal", classes = "inline") {
                                        textInput { name = "content"; placeholder = "申诉理由"; required = true }
                                        button(classes = "btn sm gray", type = ButtonType.submit) { +"提交申诉" }
                                    }
                                }
                                if (p.status == "CONFIRMED" && p.id !in rectified) {
                                    form(method = FormMethod.post, action = "/vendor/penalties/${p.id}/rectify", classes = "inline") {
                                        textInput { name = "description"; placeholder = "整改措施说明"; required = true }
                                        button(classes = "btn sm", type = ButtonType.submit) { +"提交整改（申请复检）" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post("/inspections/{id}/confirm") {
            val s = call.requireRole(Roles.VENDOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction {
                Inspections.update({ Inspections.id eq id }) { it[vendorConfirmed] = true }
            }
            call.respondRedirect("/vendor/home?msg=" + enc("已确认抽检记录 #$id"))
        }

        post("/penalties/{id}/appeal") {
            val s = call.requireRole(Roles.VENDOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            val content = call.receiveParameters()["content"] ?: ""
            transaction {
                Appeals.insert {
                    it[penaltyId] = id; it[vendorId] = s.userId
                    it[Appeals.content] = content; it[createdAt] = LocalDateTime.now()
                }
                Penalties.update({ Penalties.id eq id }) { it[status] = "APPEALING" }
            }
            call.respondRedirect("/vendor/home?msg=" + enc("申诉已提交，等待监管处理"))
        }

        post("/penalties/{id}/rectify") {
            val s = call.requireRole(Roles.VENDOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            val desc = call.receiveParameters()["description"] ?: ""
            transaction {
                val p = Penalties.selectAll().where { Penalties.id eq id }.single()
                Rectifications.insert {
                    it[penaltyId] = id; it[stallId] = p[Penalties.stallId]; it[scaleId] = p[Penalties.scaleId]
                    it[description] = desc; it[createdAt] = LocalDateTime.now()
                }
                Penalties.update({ Penalties.id eq id }) { it[status] = "RECTIFYING" }
                // 生成复检任务
                p[Penalties.scaleId]?.let { sid ->
                    InspectionTasks.insert {
                        it[scaleId] = sid; it[InspectionTasks.stallId] = p[Penalties.stallId]
                        it[reason] = "整改完成，申请复检"; it[score] = 0
                        it[generatedBy] = "REINSPECT"; it[createdAt] = LocalDateTime.now()
                    }
                    Db.logEvent(sid, "RECTIFICATION", "摊主提交整改：$desc，已生成复检任务")
                }
            }
            call.respondRedirect("/vendor/home?msg=" + enc("整改已提交，复检任务已生成"))
        }
    }
}

data class VendorInspection(
    val id: Int, val device: String, val time: String, val standard: Int,
    val displayed: Int, val errorPct: String, val result: String, val confirmed: Boolean
)
data class VendorPenalty(val id: Int, val reason: String, val amount: String, val status: String, val time: String)
