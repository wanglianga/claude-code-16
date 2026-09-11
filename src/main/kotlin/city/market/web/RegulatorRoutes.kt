package city.market.web

import city.market.auth.Roles
import city.market.auth.UserSession
import city.market.db.*
import city.market.service.PenaltyEffects
import city.market.service.Rules
import city.market.service.TaskGenerator
import city.market.service.Trust
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.ceil

fun Route.regulatorRoutes() {
    route("/reg") {

        // ---------------- 工作台 ----------------
        get("/dashboard") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val stats = transaction {
                mapOf(
                    "scales" to Scales.selectAll().count(),
                    "suspended" to Scales.selectAll().where { Scales.status eq "SUSPENDED" }.count(),
                    "pendingTasks" to InspectionTasks.selectAll().where { InspectionTasks.status eq "PENDING" }.count(),
                    "pendingComplaints" to Complaints.selectAll().where { Complaints.status eq "SUBMITTED" }.count(),
                    "issuedPenalties" to Penalties.selectAll().where { Penalties.status inList listOf("ISSUED", "APPEALING") }.count(),
                    "pendingAppeals" to Appeals.selectAll().where { Appeals.status eq "PENDING" }.count(),
                    "rectifying" to Penalties.selectAll().where { Penalties.status eq "RECTIFYING" }.count(),
                    "offlineReview" to OfflineSyncs.selectAll().where {
                        (OfflineSyncs.status eq "SYNCED") and (OfflineSyncs.pendingReview greater 0)
                    }.count()
                )
            }
            val markets = transaction {
                Markets.selectAll().map { Triple(it[Markets.name], it[Markets.creditScore], it[Markets.inspectionLevel]) }
            }
            val preview = TaskGenerator.preview()
            call.respondHtml {
                page("监管工作台", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("grid") {
                        statCard("登记电子秤", "${stats["scales"]}")
                        statCard("停用设备", "${stats["suspended"]}", stats["suspended"]!! > 0)
                        statCard("待办抽检任务", "${stats["pendingTasks"]}", stats["pendingTasks"]!! > 0)
                        statCard("待核验投诉", "${stats["pendingComplaints"]}", stats["pendingComplaints"]!! > 0)
                        statCard("待确认处罚", "${stats["issuedPenalties"]}", stats["issuedPenalties"]!! > 0)
                        statCard("待处理申诉", "${stats["pendingAppeals"]}", stats["pendingAppeals"]!! > 0)
                        statCard("整改中", "${stats["rectifying"]}")
                        statCard("待复核补传", "${stats["offlineReview"]}", stats["offlineReview"]!! > 0)
                    }
                    div("card") {
                        h2 { +"市场信用与抽检频次" }
                        table {
                            tr { th { +"市场" }; th { +"信用分" }; th { +"抽检频次" } }
                            markets.forEach { (n, c, l) ->
                                tr {
                                    td { +n }; td { +"$c" }
                                    td { badge(if (l == "HIGH") "高频" else "常规", if (l == "HIGH") "b-red" else "b-green") }
                                }
                            }
                        }
                    }
                    div("card") {
                        h2 { +"抽检任务生成（规则引擎）" }
                        p("muted") { +"评分因子：检定到期 / 近90天投诉次数 / 交易峰值 / 摊位历史处罚 / 设备离线，≥${Rules.TASK_SCORE_THRESHOLD} 分生成任务。" }
                        form(method = FormMethod.post, action = "/reg/tasks/generate", classes = "inline") {
                            button(classes = "btn", type = ButtonType.submit) { +"立即生成抽检任务" }
                        }
                        if (preview.isNotEmpty()) {
                            table {
                                tr { th { +"设备" }; th { +"风险分" }; th { +"评分因子" } }
                                preview.forEach { p ->
                                    tr { td { +p.deviceNo }; td { +"${p.score}" }; td { +p.reason } }
                                }
                            }
                        } else p("muted") { +"当前无达到阈值的设备。" }
                    }
                }
            }
        }

        post("/tasks/generate") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val n = TaskGenerator.generate()
            call.respondRedirect("/reg/tasks?msg=" + enc("已生成 $n 条抽检任务"))
        }

        // ---------------- 抽检任务 ----------------
        get("/tasks") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val rows = transaction {
                InspectionTasks
                    .join(Scales, JoinType.INNER, InspectionTasks.scaleId, Scales.id)
                    .join(Stalls, JoinType.INNER, InspectionTasks.stallId, Stalls.id)
                    .selectAll()
                    .orderBy(InspectionTasks.status to SortOrder.DESC, InspectionTasks.score to SortOrder.DESC)
                    .map {
                        TaskRow(
                            it[InspectionTasks.id], it[Scales.deviceNo], it[Stalls.stallNo],
                            it[InspectionTasks.reason], it[InspectionTasks.score],
                            it[InspectionTasks.status], it[InspectionTasks.generatedBy],
                            it[InspectionTasks.createdAt].toString()
                        )
                    }
            }
            call.respondHtml {
                page("抽检任务", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"抽检任务" }
                        table {
                            tr { th { +"设备" }; th { +"摊位" }; th { +"来源" }; th { +"风险分" }; th { +"生成原因" }; th { +"状态" }; th { +"操作" } }
                            rows.forEach { r ->
                                tr {
                                    td { +r.device }; td { +r.stall }
                                    td { +when (r.by) { "REINSPECT" -> "复检"; "MANUAL" -> "手动"; else -> "规则" } }
                                    td { +"${r.score}" }; td { +r.reason }
                                    td { badge(Labels.task(r.status), if (r.status == "PENDING") "b-yellow" else "b-green") }
                                    td {
                                        if (r.status == "PENDING") {
                                            a(href = "/reg/inspections/new?taskId=${r.id}", classes = "btn sm") { +"录入抽检" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 录入抽检 ----------------
        get("/inspections/new") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val taskId = call.request.queryParameters["taskId"]?.toIntOrNull()
            val task = taskId?.let { tid ->
                transaction {
                    (InspectionTasks innerJoin Scales).selectAll().where { InspectionTasks.id eq tid }.singleOrNull()
                        ?.let { Triple(tid, it[Scales.deviceNo], it[InspectionTasks.generatedBy]) }
                }
            }
            if (task == null) { call.respondRedirect("/reg/tasks?msg=" + enc("任务不存在")); return@get }
            call.respondHtml {
                page("录入抽检", s) {
                    div("card") {
                        h1 { +"录入抽检记录（设备 ${task.second}）" }
                        if (task.third == "REINSPECT") p { badge("整改复检任务", "b-yellow") }
                        form(method = FormMethod.post, action = "/reg/inspections/new", encType = FormEncType.multipartFormData) {
                            hiddenInput { name = "taskId"; value = "${task.first}" }
                            label { +"标准砝码重量（克）" }; numberInput { name = "standardWeightG"; value = "1000"; required = true }
                            label { +"秤显示值（克）" }; numberInput { name = "displayedWeightG"; required = true }
                            label { +"封签状态" }
                            select {
                                name = "sealStatus"
                                option { value = "INTACT"; +"完好" }
                                option { value = "BROKEN"; +"破损" }
                                option { value = "REPLACED"; +"已更换新封签" }
                            }
                            label { checkBoxInput { name = "vendorConfirmed" }; +" 摊主现场确认" }
                            label { +"现场照片" }; fileInput { name = "photo" }
                            button(classes = "btn", type = ButtonType.submit) { +"提交抽检结果" }
                        }
                        p("muted") { +"允许误差阈值 ±${Rules.ERROR_THRESHOLD_PCT}%，超标将自动停用设备并生成处罚。" }
                    }
                }
            }
        }

        post("/inspections/new") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@post
            var taskId = 0; var standard = 0; var displayed = 0
            var sealStatus = "INTACT"; var vendorConfirmed = false; var photo: String? = null
            val parts = mutableListOf<PartData>()
            val multipart = call.receiveMultipart()
            while (true) { val p = multipart.readPart() ?: break; parts += p }
            parts.forEach { part ->
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "taskId" -> taskId = part.value.toIntOrNull() ?: 0
                        "standardWeightG" -> standard = part.value.toIntOrNull() ?: 0
                        "displayedWeightG" -> displayed = part.value.toIntOrNull() ?: 0
                        "sealStatus" -> sealStatus = part.value
                        "vendorConfirmed" -> vendorConfirmed = true
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
            if (taskId == 0 || standard <= 0 || displayed <= 0) {
                call.respondRedirect("/reg/tasks?msg=" + enc("参数不完整")); return@post
            }
            val msg = recordInspection(taskId, s, standard, displayed, sealStatus, vendorConfirmed, photo)
            call.respondRedirect("/reg/tasks?msg=" + enc(msg))
        }

        // ---------------- 投诉核验 ----------------
        get("/complaints") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val rows = transaction {
                Complaints
                    .join(Stalls, JoinType.INNER, Complaints.stallId, Stalls.id)
                    .join(Markets, JoinType.INNER, Stalls.marketId, Markets.id)
                    .join(Users, JoinType.INNER, Complaints.consumerId, Users.id)
                    .selectAll()
                    .orderBy(Complaints.createdAt, SortOrder.DESC).limit(100).map {
                        RegComplaintRow(
                            it[Complaints.id], it[Users.displayName], it[Markets.name], it[Stalls.stallNo],
                            it[Complaints.product], it[Complaints.purchaseTime].toString(),
                            it[Complaints.nominalWeightG], it[Complaints.reweighedWeightG], it[Complaints.shortfallG],
                            it[Complaints.paymentRef], it[Complaints.status], it[Complaints.trustFlags],
                            it[Complaints.matchedTxId], it[Complaints.scaleId], it[Complaints.photos]
                        )
                    }
            }
            call.respondHtml {
                page("投诉核验", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"消费者投诉核验" }
                        rows.forEach { r ->
                            div("card") {
                                h2 {
                                    +"#${r.id} ${r.product}（${r.market} ${r.stall}） "
                                    badge(Labels.complaint(r.status), statusBadgeClass(r.status))
                                }
                                p {
                                    +"投诉人：${r.consumer} ｜ 购买时间：${r.time.take(16)} ｜ 标称 ${r.nominal}g ｜ 复称 ${r.reweighed}g ｜ 短少 ${r.shortfall ?: "-"}g"
                                }
                                p("muted") {
                                    +"付款单号：${r.paymentRef ?: "未提供"} ｜ 匹配交易：${r.matchedTx?.let { "#$it" } ?: "未匹配"} ｜ 关联秤：${r.scaleId?.let { "#$it" } ?: "未匹配"}"
                                }
                                if (!r.flags.isNullOrBlank()) {
                                    p {
                                        +"可信度风险："
                                        r.flags.split(",").filter { it.isNotBlank() }.forEach { badge(Trust.flagLabel(it), "b-yellow") }
                                    }
                                }
                                r.photo?.let { p { a(href = "/uploads/$it", target = "_blank") { +"查看现场照片" } } }
                                if (r.status == "SUBMITTED") {
                                    form(method = FormMethod.post, action = "/reg/complaints/${r.id}/verify", classes = "inline") {
                                        button(classes = "btn sm", type = ButtonType.submit) { +"核验属实（生成处罚）" }
                                    }
                                    form(method = FormMethod.post, action = "/reg/complaints/${r.id}/reject", classes = "inline") {
                                        button(classes = "btn sm gray", type = ButtonType.submit) { +"驳回" }
                                    }
                                }
                                if (r.status == "RESOLVED") {
                                    form(method = FormMethod.post, action = "/reg/complaints/${r.id}/followup", classes = "inline") {
                                        textInput { name = "result"; placeholder = "回访结果"; required = true }
                                        label { checkBoxInput { name = "satisfied" }; +" 消费者满意" }
                                        button(classes = "btn sm", type = ButtonType.submit) { +"登记回访" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post("/complaints/{id}/verify") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction {
                val c = Complaints.selectAll().where { Complaints.id eq id }.single()
                val shortfall = c[Complaints.shortfallG] ?: 0
                val amount = BigDecimal(if (shortfall >= 50) 500 else 200)
                Complaints.update({ Complaints.id eq id }) { it[status] = "VERIFIED" }
                val pid = Penalties.insert {
                    it[stallId] = c[Complaints.stallId]; it[scaleId] = c[Complaints.scaleId]
                    it[complaintId] = id; it[Penalties.amount] = amount
                    it[reason] = "投诉核验属实：${c[Complaints.product]} 标称 ${c[Complaints.nominalWeightG]}g 复称 ${c[Complaints.reweighedWeightG]}g，短少 ${shortfall}g"
                    it[status] = "ISSUED"; it[issuedBy] = s.userId; it[createdAt] = LocalDateTime.now()
                } get Penalties.id
                c[Complaints.scaleId]?.let { sid ->
                    Db.logEvent(sid, "COMPLAINT_VERIFIED", "投诉 #$id 核验属实，短少 ${shortfall}g")
                    Db.logEvent(sid, "PENALTY_ISSUED", "处罚 #$pid 开出：罚款 $amount 元")
                }
            }
            call.respondRedirect("/reg/complaints?msg=" + enc("投诉 #$id 已核验属实，处罚已开出"))
        }

        post("/complaints/{id}/reject") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction { Complaints.update({ Complaints.id eq id }) { it[status] = "REJECTED" } }
            call.respondRedirect("/reg/complaints?msg=" + enc("投诉 #$id 已驳回"))
        }

        post("/complaints/{id}/followup") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            val p = call.receiveParameters()
            transaction {
                FollowUps.insert {
                    it[complaintId] = id
                    it[result] = p["result"] ?: "电话回访"
                    it[satisfied] = p["satisfied"] != null
                    it[createdAt] = LocalDateTime.now()
                }
                Complaints.update({ Complaints.id eq id }) { it[status] = "FOLLOWED_UP" }
                Complaints.selectAll().where { Complaints.id eq id }.single()[Complaints.scaleId]?.let { sid ->
                    Db.logEvent(sid, "FOLLOW_UP", "投诉 #$id 回访：${p["result"]}")
                }
            }
            call.respondRedirect("/reg/complaints?msg=" + enc("回访已登记"))
        }

        // ---------------- 处罚管理 ----------------
        get("/penalties") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val rows = transaction {
                (Penalties innerJoin Stalls).selectAll().orderBy(Penalties.createdAt, SortOrder.DESC).limit(100).map {
                    PenaltyRow(
                        it[Penalties.id], it[Stalls.stallNo], it[Penalties.scaleId],
                        it[Penalties.amount].toPlainString(), it[Penalties.reason], it[Penalties.status],
                        it[Penalties.createdAt].toString(), it[Penalties.complaintId], it[Penalties.inspectionId]
                    )
                }
            }
            val appeals = transaction {
                Appeals.selectAll().where { Appeals.status eq "PENDING" }.map {
                    AppealRow(it[Appeals.id], it[Appeals.penaltyId], it[Appeals.content], it[Appeals.createdAt].toString())
                }
            }
            call.respondHtml {
                page("处罚管理", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"处罚记录" }
                        table {
                            tr { th { +"#" }; th { +"摊位" }; th { +"事由" }; th { +"罚款" }; th { +"来源" }; th { +"状态" }; th { +"操作" } }
                            rows.forEach { r ->
                                tr {
                                    td { +"${r.id}" }; td { +r.stall }; td { +r.reason }; td { +"¥${r.amount}" }
                                    td { +if (r.complaintId != null) "投诉#${r.complaintId}" else if (r.inspectionId != null) "抽检#${r.inspectionId}" else "手动" }
                                    td { badge(Labels.penalty(r.status), statusBadgeClass(r.status)) }
                                    td {
                                        if (r.status == "ISSUED") {
                                            form(method = FormMethod.post, action = "/reg/penalties/${r.id}/confirm", classes = "inline") {
                                                button(classes = "btn sm", type = ButtonType.submit) { +"确认生效" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (appeals.isNotEmpty()) {
                        div("card") {
                            h2 { +"待处理申诉" }
                            appeals.forEach { a ->
                                div("card") {
                                    p { +"处罚 #${a.penaltyId} ｜ 申诉内容：${a.content}" }
                                    form(method = FormMethod.post, action = "/reg/appeals/${a.id}/resolve", classes = "inline") {
                                        hiddenInput { name = "decision"; value = "ACCEPTED" }
                                        textInput { name = "resolution"; placeholder = "处理意见"; required = true }
                                        button(classes = "btn sm", type = ButtonType.submit) { +"申诉成立（撤销处罚）" }
                                    }
                                    form(method = FormMethod.post, action = "/reg/appeals/${a.id}/resolve", classes = "inline") {
                                        hiddenInput { name = "decision"; value = "REJECTED" }
                                        textInput { name = "resolution"; placeholder = "处理意见"; required = true }
                                        button(classes = "btn sm gray", type = ButtonType.submit) { +"驳回申诉" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post("/penalties/{id}/confirm") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            PenaltyEffects.confirm(id)
            call.respondRedirect("/reg/penalties?msg=" + enc("处罚 #$id 已确认：信用扣减、公示发布、抽检频次已更新"))
        }

        post("/appeals/{id}/resolve") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            val p = call.receiveParameters()
            val decision = p["decision"] ?: "REJECTED"
            val resolution = p["resolution"] ?: ""
            transaction {
                val a = Appeals.selectAll().where { Appeals.id eq id }.single()
                val penaltyId = a[Appeals.penaltyId]
                Appeals.update({ Appeals.id eq id }) {
                    it[status] = decision; it[Appeals.resolution] = resolution
                }
                if (decision == "ACCEPTED") {
                    Penalties.update({ Penalties.id eq penaltyId }) { it[status] = "CANCELLED" }
                    Penalties.selectAll().where { Penalties.id eq penaltyId }.single()[Penalties.scaleId]?.let { sid ->
                        Db.logEvent(sid, "PENALTY_ISSUED", "处罚 #$penaltyId 经申诉撤销：$resolution")
                    }
                } else {
                    Penalties.update({ Penalties.id eq penaltyId }) { it[status] = "ISSUED" }
                }
            }
            call.respondRedirect("/reg/penalties?msg=" + enc("申诉已处理"))
        }

        // ---------------- 公示管理 ----------------
        get("/disclosures") {
            val s = call.requireRole(Roles.REGULATOR) ?: return@get
            val rows = transaction {
                (Disclosures innerJoin Markets).selectAll()
                    .orderBy(Disclosures.publishedAt, SortOrder.DESC).limit(100).map {
                        DisclosureView(it[Disclosures.id], it[Markets.name], it[Disclosures.title], it[Disclosures.content], it[Disclosures.status], it[Disclosures.publishedAt].toString())
                    }
            }
            call.respondHtml {
                page("公示管理", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"公示管理" }
                        rows.forEach { d ->
                            div("card") {
                                h2 { +"${d.title} "; badge(Labels.disclosure(d.status), if (d.status == "PUBLISHED") "b-green" else "") }
                                p { +d.content }
                                p("muted") { +"${d.market} ｜ ${d.time.take(16)}" }
                                if (d.status == "PUBLISHED") {
                                    form(method = FormMethod.post, action = "/reg/disclosures/${d.id}/withdraw", classes = "inline") {
                                        button(classes = "btn sm gray", type = ButtonType.submit) { +"撤回公示" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post("/disclosures/{id}/withdraw") {
            call.requireRole(Roles.REGULATOR) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction { Disclosures.update({ Disclosures.id eq id }) { it[status] = "WITHDRAWN" } }
            call.respondRedirect("/reg/disclosures?msg=" + enc("公示已撤回"))
        }

        // ---------------- 秤监管档案 ----------------
        get("/scales/{id}") {
            val s = call.requireRole(Roles.REGULATOR, Roles.MARKET_ADMIN, Roles.VENDOR) ?: return@get
            val id = call.parameters["id"]!!.toInt()
            val scale = transaction {
                (Scales innerJoin Stalls innerJoin Markets).selectAll().where { Scales.id eq id }.singleOrNull()
            } ?: run { call.respondRedirect("/"); return@get }
            if (s.role == Roles.VENDOR && scale[Stalls.vendorId] != s.userId) {
                call.respondRedirect("/vendor/home?msg=" + enc("只能查看自己摊位的秤档案")); return@get
            }
            val events = transaction {
                ScaleEvents.selectAll().where { ScaleEvents.scaleId eq id }
                    .orderBy(ScaleEvents.createdAt, SortOrder.DESC).limit(200)
                    .map { Triple(it[ScaleEvents.eventType], it[ScaleEvents.detail], it[ScaleEvents.createdAt].toString()) }
            }
            val inspections = transaction {
                Inspections.selectAll().where { Inspections.scaleId eq id }
                    .orderBy(Inspections.createdAt, SortOrder.DESC).limit(20).map {
                        InspectionRow(
                            it[Inspections.createdAt].toString(), it[Inspections.kind],
                            it[Inspections.standardWeightG], it[Inspections.displayedWeightG],
                            it[Inspections.errorG], it[Inspections.errorPct].toPlainString(),
                            it[Inspections.sealStatus], it[Inspections.result], it[Inspections.vendorConfirmed]
                        )
                    }
            }
            val sealChanges = transaction {
                SealChanges.selectAll().where { SealChanges.scaleId eq id }
                    .orderBy(SealChanges.createdAt, SortOrder.DESC).map {
                        Triple(it[SealChanges.reason], it[SealChanges.newSealPhoto], it[SealChanges.createdAt].toString())
                    }
            }
            call.respondHtml {
                page("秤监管档案", s) {
                    div("card") {
                        h1 { +"设备 ${scale[Scales.deviceNo]} 监管档案" }
                        p {
                            +"摊位：${scale[Markets.name]} ${scale[Stalls.stallNo]}（${scale[Scales.category]}）｜ 证书：${scale[Scales.certNo]}（有效期至 ${scale[Scales.certValidUntil]}）"
                        }
                        p {
                            +"状态："; badge(Labels.scale(scale[Scales.status]), statusBadgeClass(scale[Scales.status]))
                            +" 联网："; badge(if (scale[Scales.online]) "在线" else "离线", if (scale[Scales.online]) "b-green" else "b-red")
                            if (scale[Scales.shared]) { +" "; badge("多人共用", "b-yellow") }
                            +" 绑定时间：${scale[Scales.boundAt].toString().take(16)}"
                        }
                        scale[Scales.sealPhoto]?.let { p { a(href = "/uploads/$it", target = "_blank") { +"查看当前封签照片" } } }
                    }
                    div("card") {
                        h2 { +"抽检记录" }
                        table {
                            tr { th { +"时间" }; th { +"类型" }; th { +"标准/显示(g)" }; th { +"误差" }; th { +"封签" }; th { +"结果" }; th { +"摊主确认" } }
                            inspections.forEach { r ->
                                tr {
                                    td { +r.time.take(16) }; td { +if (r.kind == "REINSPECT") "复检" else "抽检" }
                                    td { +"${r.standard} / ${r.displayed}" }
                                    td { +"${r.errorG}g（${r.errorPct}%）" }
                                    td { +Labels.seal(r.seal) }
                                    td { badge(Labels.result(r.result), if (r.result == "PASS") "b-green" else "b-red") }
                                    td { +if (r.confirmed) "已确认" else "未确认" }
                                }
                            }
                        }
                    }
                    if (sealChanges.isNotEmpty()) {
                        div("card") {
                            h2 { +"封签更换记录" }
                            sealChanges.forEach { (reason, photo, time) ->
                                p { +"$time ｜ $reason"; photo?.let { a(href = "/uploads/$it", target = "_blank") { +"（新封签照片）" } } }
                            }
                        }
                    }
                    div("card") {
                        h2 { +"监管事件时间线" }
                        events.forEach { (type, detail, time) ->
                            p { badge(Labels.event(type)); +" $time ｜ $detail" }
                        }
                    }
                }
            }
        }
    }
}

/** 录入抽检：计算误差，超标触发停用+处罚；复检合格触发解封+封签更换+整改闭环 */
private fun recordInspection(
    taskId: Int, s: UserSession, standard: Int, displayed: Int,
    sealStatus: String, vendorConfirmed: Boolean, photo: String?
): String = transaction {
    val task = InspectionTasks.selectAll().where { InspectionTasks.id eq taskId }.single()
    val scaleId = task[InspectionTasks.scaleId]
    val isReinspect = task[InspectionTasks.generatedBy] == "REINSPECT"
    val errorG = displayed - standard
    val errorPct = BigDecimal(errorG * 100.0 / standard).setScale(4, RoundingMode.HALF_UP)
    val over = abs(errorPct.toDouble()) > Rules.ERROR_THRESHOLD_PCT
    val now = LocalDateTime.now()

    val inspId = Inspections.insert {
        it[Inspections.taskId] = taskId; it[Inspections.scaleId] = scaleId
        it[inspectorId] = s.userId
        it[standardWeightG] = standard; it[displayedWeightG] = displayed
        it[Inspections.errorG] = errorG; it[Inspections.errorPct] = errorPct
        it[Inspections.sealStatus] = sealStatus; it[Inspections.vendorConfirmed] = vendorConfirmed
        it[photos] = photo; it[result] = if (over) "OVER_ERROR" else "PASS"
        it[kind] = if (isReinspect) "REINSPECT" else "SPOT"; it[createdAt] = now
    } get Inspections.id
    InspectionTasks.update({ InspectionTasks.id eq taskId }) { it[status] = "DONE" }

    if (!isReinspect) {
        if (over) {
            // 误差超标：停用 + 自动处罚
            Scales.update({ Scales.id eq scaleId }) { it[status] = "SUSPENDED" }
            val amount = BigDecimal(500 + ceil(abs(errorPct.toDouble())).toInt() * 100)
            val pid = Penalties.insert {
                it[stallId] = task[InspectionTasks.stallId]; it[Penalties.scaleId] = scaleId
                it[inspectionId] = inspId; it[complaintId] = null
                it[Penalties.amount] = amount
                it[reason] = "抽检误差超标：标准 ${standard}g 显示 ${displayed}g（误差 ${errorPct}%）"
                it[status] = "ISSUED"; it[issuedBy] = s.userId; it[createdAt] = now
            } get Penalties.id
            Db.logEvent(scaleId, "INSPECTION_OVER", "抽检误差 ${errorG}g（${errorPct}%）超标，设备停用", now)
            Db.logEvent(scaleId, "SUSPENDED", "误差超标，设备停用", now)
            Db.logEvent(scaleId, "PENALTY_ISSUED", "处罚 #$pid 开出：罚款 $amount 元", now)
            "误差 ${errorPct}% 超标：设备已停用，处罚 #$pid 已开出（¥$amount）"
        } else {
            Db.logEvent(scaleId, "INSPECTION_PASS", "抽检合格：误差 ${errorG}g（${errorPct}%）", now)
            "抽检合格：误差 ${errorPct}%"
        }
    } else {
        // 复检流程
        if (!over) {
            Scales.update({ Scales.id eq scaleId }) { it[status] = "ACTIVE" }
            // 封签更换记录
            if (photo != null || sealStatus == "REPLACED") {
                val old = Scales.selectAll().where { Scales.id eq scaleId }.single()[Scales.sealPhoto]
                SealChanges.insert {
                    it[SealChanges.scaleId] = scaleId
                    it[oldSealPhoto] = old; it[newSealPhoto] = photo ?: old
                    it[reason] = "整改复检合格，更换封签"
                    it[changedBy] = s.userId; it[createdAt] = now
                }
                if (photo != null) Scales.update({ Scales.id eq scaleId }) { it[sealPhoto] = photo }
                Db.logEvent(scaleId, "SEAL_CHANGED", "复检合格，封签已更换", now)
            }
            // 整改闭环：找到该秤最近一条 RECTIFYING 的处罚
            val rectifying = Penalties.selectAll().where {
                (Penalties.scaleId eq scaleId) and (Penalties.status eq "RECTIFYING")
            }.orderBy(Penalties.createdAt, SortOrder.DESC).limit(1).singleOrNull()
            rectifying?.let { p ->
                val pid = p[Penalties.id]
                Penalties.update({ Penalties.id eq pid }) { it[status] = "RECTIFIED" }
                Rectifications.update({ Rectifications.penaltyId eq pid }) {
                    it[status] = "REINSPECT_PASSED"; it[completedAt] = now
                }
                Db.logEvent(scaleId, "REINSPECT_PASS", "整改复检合格，处罚 #$pid 整改完成", now)
            }
            Db.logEvent(scaleId, "REACTIVATED", "复检合格，设备恢复使用", now)
            "复检合格：设备恢复使用，整改闭环完成"
        } else {
            Db.logEvent(scaleId, "INSPECTION_OVER", "复检仍超标：误差 ${errorPct}%，设备维持停用", now)
            "复检仍超标（${errorPct}%）：设备维持停用，需继续整改"
        }
    }
}

private fun DIV.statCard(label: String, value: String, warn: Boolean = false) {
    div("stat") {
        div(if (warn) "n b-red" else "n") { +value }
        div { +label }
    }
}

data class TaskRow(val id: Int, val device: String, val stall: String, val reason: String, val score: Int, val status: String, val by: String, val time: String)
data class RegComplaintRow(
    val id: Int, val consumer: String, val market: String, val stall: String, val product: String,
    val time: String, val nominal: Int, val reweighed: Int, val shortfall: Int?,
    val paymentRef: String?, val status: String, val flags: String?, val matchedTx: Long?,
    val scaleId: Int?, val photo: String?
)
data class PenaltyRow(
    val id: Int, val stall: String, val scaleId: Int?, val amount: String, val reason: String,
    val status: String, val time: String, val complaintId: Int?, val inspectionId: Int?
)
data class AppealRow(val id: Int, val penaltyId: Int, val content: String, val time: String)
data class InspectionRow(
    val time: String, val kind: String, val standard: Int, val displayed: Int,
    val errorG: Int, val errorPct: String, val seal: String, val result: String, val confirmed: Boolean
)
