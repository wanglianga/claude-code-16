package city.market.web

import city.market.auth.Roles
import city.market.db.Markets
import city.market.service.Reports
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

fun Route.reportRoutes() {
    route("/reg/reports") {

        // 处罚后变化追踪：按市场/摊位/设备/投诉人维度
        get("/tracking") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val dim = call.request.queryParameters["dim"] ?: "market"
            val rows = Reports.tracking(dim)
            call.respondHtml {
                page("处罚后变化追踪", s) {
                    div("card") {
                        h1 { +"处罚后变化追踪（首次确认处罚前后各 90 天）" }
                        p {
                            +"维度："
                            listOf("market" to "市场", "stall" to "摊位", "device" to "设备", "complainant" to "投诉人").forEach { (k, l) ->
                                if (k == dim) badge(l, "b-green") else a(href = "/reg/reports/tracking?dim=$k", classes = "btn sm gray") { +l }
                                +" "
                            }
                        }
                        if (rows.isEmpty()) p("muted") { +"暂无已确认处罚，无法对比。" }
                        else table {
                            tr {
                                th { +"对象" }; th { +"处罚前投诉" }; th { +"处罚前属实" }
                                th { +"处罚后投诉" }; th { +"处罚后属实" }; th { +"变化" }
                                th { +"处罚次数" }; th { +"罚款合计" }
                            }
                            rows.forEach { r ->
                                val delta = r.verifiedAfter - r.verifiedBefore
                                tr {
                                    td { +r.key }
                                    td { +"${r.complaintsBefore}" }; td { +"${r.verifiedBefore}" }
                                    td { +"${r.complaintsAfter}" }; td { +"${r.verifiedAfter}" }
                                    td {
                                        badge(
                                            if (delta < 0) "下降 ${-delta}" else if (delta == 0L) "持平" else "上升 $delta",
                                            if (delta < 0) "b-green" else if (delta == 0L) "" else "b-red"
                                        )
                                    }
                                    td { +"${r.penalties}" }; td { +"¥${r.fineTotal}" }
                                }
                            }
                        }
                        p("muted") { +"“属实”指核验成立或已办结的投诉。处罚后属实投诉下降，说明整改有效。" }
                    }
                }
            }
        }

        // 年度治理材料
        get("/annual") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val year = call.request.queryParameters["year"]?.toIntOrNull() ?: LocalDate.now().year
            val markets = transaction { Markets.selectAll().map { it[Markets.id] to it[Markets.name] } }
            val marketId = call.request.queryParameters["marketId"]?.toIntOrNull() ?: markets.firstOrNull()?.first
            if (marketId == null) { call.respondRedirect("/reg/dashboard"); return@get }
            val r = Reports.annual(year, marketId)
            call.respondHtml {
                page("年度治理材料", s) {
                    div("card") {
                        h1 { +"${year} 年度短斤少两治理材料 · ${r.marketName}" }
                        form(method = FormMethod.get, action = "/reg/reports/annual", classes = "inline") {
                            label { +"年份 " }; numberInput { name = "year"; value = "$year" }
                            label { +" 市场 " }
                            select {
                                name = "marketId"
                                markets.forEach { (id, n) ->
                                    option { value = "$id"; if (id == marketId) selected = true; +n }
                                }
                            }
                            button(classes = "btn sm", type = ButtonType.submit) { +"切换" }
                        }
                    }
                    div("grid") {
                        statCard2("抽检次数", "${r.inspections}")
                        statCard2("误差超标", "${r.overError}")
                        statCard2("投诉总量", "${r.complaints}")
                        statCard2("核验属实", "${r.verified}")
                        statCard2("处罚起数", "${r.penalties}")
                        statCard2("罚款合计", "¥${r.fineTotal}")
                        statCard2("申诉/成立", "${r.appeals}/${r.appealsAccepted}")
                        statCard2("整改/完成", "${r.rectifications}/${r.rectified}")
                        statCard2("公示发布", "${r.disclosures}")
                        statCard2("投诉回访", "${r.followUps}")
                        statCard2("市场信用分", "${r.creditScore}")
                        statCard2("抽检频次", if (r.inspectionLevel == "HIGH") "高频" else "常规")
                    }
                    div("card") {
                        h2 { +"投诉高发摊位 TOP5" }
                        if (r.topStalls.isEmpty()) p("muted") { +"无数据" }
                        else table {
                            tr { th { +"摊位" }; th { +"投诉次数" } }
                            r.topStalls.forEach { (no, n) -> tr { td { +no }; td { +"$n" } } }
                        }
                    }
                    div("card") {
                        h2 { +"治理链条说明" }
                        p {
                            +"本年度共完成抽检 ${r.inspections} 次，其中误差超标 ${r.overError} 次；受理投诉 ${r.complaints} 起，核验属实 ${r.verified} 起；"
                            +"开出处罚 ${r.penalties} 起、罚款合计 ¥${r.fineTotal}，受理申诉 ${r.appeals} 起（成立 ${r.appealsAccepted} 起）；"
                            +"整改申请 ${r.rectifications} 起、复检通过 ${r.rectified} 起；发布公示 ${r.disclosures} 期，完成投诉回访 ${r.followUps} 次。"
                            +"当前市场信用分 ${r.creditScore}，抽检频次为「${if (r.inspectionLevel == "HIGH") "高频" else "常规"}」。"
                        }
                        p("muted") { +"公示、申诉、复检与市场评分数据均来自同一监管档案，可作为年度治理考核材料导出存档。" }
                    }
                }
            }
        }
    }
}

private fun DIV.statCard2(label: String, value: String) {
    div("stat") {
        div("n") { +value }
        div { +label }
    }
}
