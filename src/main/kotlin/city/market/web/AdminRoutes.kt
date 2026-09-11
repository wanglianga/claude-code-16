package city.market.web

import city.market.auth.Roles
import city.market.db.*
import city.market.saveUpload
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
import java.time.LocalDate
import java.time.LocalDateTime

fun Route.adminRoutes() {
    route("/admin") {

        // ---- 电子秤台账 ----
        get("/scales") {
            val s = call.requireRole(Roles.MARKET_ADMIN, Roles.REGULATOR) ?: return@get
            val rows = transaction {
                (Scales innerJoin Stalls innerJoin Markets).selectAll()
                    .orderBy(Scales.id).map {
                        ScaleRow(
                            it[Scales.id], it[Scales.deviceNo], it[Markets.name], it[Stalls.stallNo],
                            it[Scales.category], it[Scales.certNo], it[Scales.certValidUntil].toString(),
                            it[Scales.online], it[Scales.status], it[Scales.shared], it[Scales.sealPhoto]
                        )
                    }
            }
            call.respondHtml {
                page("电子秤台账", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"电子秤登记台账" }
                        if (s.role == Roles.MARKET_ADMIN) {
                            p { a(href = "/admin/scales/new", classes = "btn") { +"＋ 登记新秤" } }
                        }
                        table {
                            tr { th { +"设备编号" }; th { +"市场/摊位" }; th { +"品类" }; th { +"检定证书" }; th { +"有效期" }; th { +"封签" }; th { +"联网" }; th { +"状态" }; th { +"档案" } }
                            rows.forEach { r ->
                                tr {
                                    td { +r.deviceNo; if (r.shared) badge("共用", "b-yellow") }
                                    td { +"${r.market} ${r.stall}" }
                                    td { +r.category }
                                    td { +r.certNo }
                                    td {
                                        val expired = LocalDate.parse(r.certUntil) < LocalDate.now()
                                        span(if (expired) "b-red badge" else "") { +r.certUntil }
                                    }
                                    td { r.seal?.let { a(href = "/uploads/$it", target = "_blank") { +"查看" } } ?: +"—" }
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
                }
            }
        }

        get("/scales/new") {
            val s = call.requireRole(Roles.MARKET_ADMIN) ?: return@get
            val stalls = transaction {
                (Stalls innerJoin Markets).selectAll().orderBy(Stalls.id)
                    .map { it[Stalls.id] to "${it[Markets.name]} ${it[Stalls.stallNo]}（${it[Stalls.category]}）" }
            }
            call.respondHtml {
                page("登记电子秤", s) {
                    div("card") {
                        h1 { +"登记新电子秤" }
                        form(method = FormMethod.post, action = "/admin/scales/new", encType = FormEncType.multipartFormData) {
                            label { +"设备编号" }; textInput { name = "deviceNo"; required = true }
                            label { +"所属摊位" }
                            select {
                                name = "stallId"
                                stalls.forEach { (id, label) -> option { value = id.toString(); +label } }
                            }
                            label { +"经营品类" }; textInput { name = "category"; required = true }
                            label { +"检定证书编号" }; textInput { name = "certNo"; required = true }
                            label { +"证书有效期至" }; dateInput { name = "certValidUntil"; required = true }
                            label { +"封签照片" }; fileInput { name = "sealPhoto" }
                            label {
                                checkBoxInput { name = "shared" }; +" 多人共用一台秤"
                            }
                            button(classes = "btn", type = ButtonType.submit) { +"登记" }
                        }
                    }
                }
            }
        }

        post("/scales/new") {
            val s = call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val multipart = call.receiveMultipart()
            var deviceNo = ""; var stallId = 0; var category = ""; var certNo = ""
            var certUntil = ""; var shared = false
            var sealPhoto: String? = null
            // 先收集字段，再处理文件
            val parts = mutableListOf<PartData>()
            while (true) { val p = multipart.readPart() ?: break; parts += p }
            parts.forEach { part ->
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "deviceNo" -> deviceNo = part.value
                        "stallId" -> stallId = part.value.toIntOrNull() ?: 0
                        "category" -> category = part.value
                        "certNo" -> certNo = part.value
                        "certValidUntil" -> certUntil = part.value
                        "shared" -> shared = true
                    }
                    is PartData.FileItem -> if (part.name == "sealPhoto" && !part.originalFileName.isNullOrBlank()) {
                        val ext = part.originalFileName!!.substringAfterLast('.', "jpg").replace(Regex("[^a-zA-Z0-9]"), "")
                        val name = "${java.util.UUID.randomUUID()}.$ext"
                        java.io.File(Db.uploadDir, name).writeBytes(part.provider().readBytes())
                        sealPhoto = name
                    }
                    else -> {}
                }
                part.dispose()
            }
            if (deviceNo.isBlank() || stallId == 0 || certUntil.isBlank()) {
                call.respondRedirect("/admin/scales?msg=" + enc("必填项缺失"))
                return@post
            }
            val dup = transaction { Scales.selectAll().where { Scales.deviceNo eq deviceNo }.any() }
            if (dup) { call.respondRedirect("/admin/scales?msg=" + enc("设备编号已存在")); return@post }
            val id = transaction {
                val now = LocalDateTime.now()
                val id = Scales.insert {
                    it[Scales.deviceNo] = deviceNo; it[Scales.stallId] = stallId
                    it[Scales.category] = category; it[Scales.certNo] = certNo
                    it[Scales.certValidUntil] = LocalDate.parse(certUntil)
                    it[Scales.sealPhoto] = sealPhoto; it[Scales.shared] = shared
                    it[boundAt] = now; it[registeredAt] = now
                } get Scales.id
                Db.logEvent(id, "REGISTERED", "市场管理方登记设备（证书 $certNo，有效期至 $certUntil）")
                id
            }
            call.respondRedirect("/admin/scales?msg=" + enc("登记成功，设备编号 $deviceNo"))
        }

        post("/scales/{id}/toggle-online") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction {
                val cur = Scales.selectAll().where { Scales.id eq id }.single()[Scales.online]
                Scales.update({ Scales.id eq id }) { it[online] = !cur }
                Db.logEvent(id, if (cur) "OFFLINE" else "ONLINE", if (cur) "设备离线" else "设备恢复联网")
            }
            call.respondRedirect("/admin/scales?msg=" + enc("联网状态已切换"))
        }

        // ---- 摊位管理 ----
        get("/stalls") {
            val s = call.requireRole(Roles.MARKET_ADMIN) ?: return@get
            val rows = transaction {
                (Stalls innerJoin Markets).selectAll().orderBy(Stalls.id).map {
                    val vendor = it[Stalls.vendorId]?.let { v ->
                        Users.selectAll().where { Users.id eq v }.singleOrNull()?.get(Users.displayName)
                    } ?: "未分配"
                    StallRow(it[Stalls.id], it[Markets.name], it[Stalls.stallNo], it[Stalls.category], vendor, it[Stalls.creditScore], it[Stalls.penaltyCount])
                }
            }
            val markets = transaction { Markets.selectAll().map { it[Markets.id] to it[Markets.name] } }
            val vendors = transaction {
                Users.selectAll().where { Users.role eq Roles.VENDOR }.map { it[Users.id] to it[Users.displayName] }
            }
            call.respondHtml {
                page("摊位管理", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"摊位管理" }
                        table {
                            tr { th { +"市场" }; th { +"摊位号" }; th { +"品类" }; th { +"摊主" }; th { +"信用分" }; th { +"处罚次数" } }
                            rows.forEach { r ->
                                tr {
                                    td { +r.market }; td { +r.no }; td { +r.category }; td { +r.vendor }
                                    td { +"${r.credit}" }; td { +"${r.penalties}" }
                                }
                            }
                        }
                    }
                    div("card") {
                        h2 { +"新增摊位" }
                        form(method = FormMethod.post, action = "/admin/stalls/new") {
                            label { +"所属市场" }
                            select { name = "marketId"; markets.forEach { (id, n) -> option { value = "$id"; +n } } }
                            label { +"摊位号" }; textInput { name = "stallNo"; required = true }
                            label { +"经营品类" }; textInput { name = "category"; required = true }
                            label { +"摊主" }
                            select { name = "vendorId"; vendors.forEach { (id, n) -> option { value = "$id"; +n } } }
                            button(classes = "btn", type = ButtonType.submit) { +"新增" }
                        }
                    }
                }
            }
        }

        post("/stalls/new") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val p = call.receiveParameters()
            transaction {
                Stalls.insert {
                    it[marketId] = p["marketId"]!!.toInt()
                    it[stallNo] = p["stallNo"]!!
                    it[category] = p["category"]!!
                    it[vendorId] = p["vendorId"]?.toIntOrNull()
                }
            }
            call.respondRedirect("/admin/stalls?msg=" + enc("摊位已新增"))
        }

        get("/disclosures") { call.respondRedirect("/disclosures") }

        // ---- 多人共用秤：排班与收款码管理 ----
        get("/sharing") {
            val s = call.requireRole(Roles.MARKET_ADMIN) ?: return@get
            val scales = transaction {
                (Scales innerJoin Stalls innerJoin Markets).selectAll().orderBy(Scales.id).map {
                    Triple(it[Scales.id], it[Scales.deviceNo], "${it[Markets.name]} ${it[Stalls.stallNo]}（备案）")
                }
            }
            val stalls = transaction {
                (Stalls innerJoin Markets).selectAll().orderBy(Stalls.id)
                    .map { it[Stalls.id] to "${it[Markets.name]} ${it[Stalls.stallNo]}（${it[Stalls.category]}）" }
            }
            val schedules = transaction {
                ScaleSchedules
                    .join(Scales, JoinType.INNER, ScaleSchedules.scaleId, Scales.id)
                    .join(Stalls, JoinType.INNER, ScaleSchedules.stallId, Stalls.id)
                    .selectAll()
                    .orderBy(ScaleSchedules.scaleId to SortOrder.ASC, ScaleSchedules.startHour to SortOrder.ASC).map {
                        SharingRow(
                            it[ScaleSchedules.id], it[Scales.deviceNo], it[Stalls.stallNo],
                            if (it[ScaleSchedules.slot] == "EVENING") "晚市" else "早市",
                            it[ScaleSchedules.startHour], it[ScaleSchedules.endHour]
                        )
                    }
            }
            val codes = transaction {
                (PaymentCodes innerJoin Stalls).selectAll().orderBy(PaymentCodes.id).map {
                    PayCodeRow(it[PaymentCodes.id], it[Stalls.stallNo], it[PaymentCodes.prefix], it[PaymentCodes.channel])
                }
            }
            call.respondHtml {
                page("共用秤排班与收款码", s) {
                    msgBox(call.request.queryParameters["msg"])
                    div("card") {
                        h1 { +"多人共用秤 · 早晚市排班与收款码" }
                        p("muted") { +"相邻摊位高峰共用一台电子秤时，在此登记各摊位早/晚市班次和收款码前缀；投诉责任认定将按付款码、排班、交易时间、商品品类、监控备注判定实际经营者。" }
                        h2 { +"当前排班" }
                        if (schedules.isEmpty()) p("muted") { +"暂无共用排班" }
                        else table {
                            tr { th { +"设备" }; th { +"摊位" }; th { +"班次" }; th { +"时段" }; th { +"操作" } }
                            schedules.forEach { r ->
                                tr {
                                    td { +r.device }; td { +r.stall }; td { +r.slot }
                                    td { +"${r.startH}:00 - ${r.endH}:00" }
                                    td {
                                        form(method = FormMethod.post, action = "/admin/sharing/schedules/${r.id}/delete", classes = "inline") {
                                            button(classes = "btn sm gray", type = ButtonType.submit) { +"删除" }
                                        }
                                    }
                                }
                            }
                        }
                        h2 { +"新增排班" }
                        form(method = FormMethod.post, action = "/admin/sharing/schedules/new") {
                            label { +"共用电子秤" }
                            select { name = "scaleId"; scales.forEach { (id, dev, label) -> option { value = "$id"; +"$dev · $label" } } }
                            label { +"使用摊位" }
                            select { name = "stallId"; stalls.forEach { (id, l) -> option { value = "$id"; +l } } }
                            label { +"班次" }
                            select {
                                name = "slot"
                                option { value = "MORNING"; +"早市" }
                                option { value = "EVENING"; +"晚市" }
                            }
                            label { +"开始/结束小时（24 小时制，结束小时不含）" }
                            div {
                                numberInput { name = "startHour"; value = "7"; attributes["min"] = "0"; attributes["max"] = "23" }
                                numberInput { name = "endHour"; value = "9"; attributes["min"] = "1"; attributes["max"] = "24" }
                            }
                            button(classes = "btn", type = ButtonType.submit) { +"登记排班（自动标记共用秤）" }
                        }
                    }
                    div("card") {
                        h2 { +"摊位收款码前缀" }
                        if (codes.isEmpty()) p("muted") { +"暂无收款码登记" }
                        else table {
                            tr { th { +"摊位" }; th { +"单号前缀" }; th { +"渠道" }; th { +"操作" } }
                            codes.forEach { c ->
                                tr {
                                    td { +c.stall }; td { +c.prefix }; td { +c.channel }
                                    td {
                                        form(method = FormMethod.post, action = "/admin/sharing/codes/${c.id}/delete", classes = "inline") {
                                            button(classes = "btn sm gray", type = ButtonType.submit) { +"删除" }
                                        }
                                    }
                                }
                            }
                        }
                        form(method = FormMethod.post, action = "/admin/sharing/codes/new") {
                            label { +"摊位" }
                            select { name = "stallId"; stalls.forEach { (id, l) -> option { value = "$id"; +l } } }
                            label { +"付款单号前缀（如 ZFB-S5-）" }; textInput { name = "prefix"; required = true }
                            label { +"收款渠道" }
                            select {
                                name = "channel"
                                option { value = "WECHAT"; +"微信" }
                                option { value = "ALIPAY"; +"支付宝" }
                                option { value = "CASH"; +"现金/其他" }
                            }
                            button(classes = "btn", type = ButtonType.submit) { +"登记收款码" }
                        }
                    }
                }
            }
        }

        post("/sharing/schedules/new") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val p = call.receiveParameters()
            val scaleId = p["scaleId"]?.toIntOrNull() ?: 0
            val stallId = p["stallId"]?.toIntOrNull() ?: 0
            val startH = (p["startHour"]?.toIntOrNull() ?: 0).coerceIn(0, 23)
            val endH = (p["endHour"]?.toIntOrNull() ?: 0).coerceIn(1, 24)
            if (scaleId == 0 || stallId == 0 || endH <= startH) {
                call.respondRedirect("/admin/sharing?msg=" + enc("排班参数不完整或时段错误")); return@post
            }
            transaction {
                val dup = ScaleSchedules.selectAll().where {
                    (ScaleSchedules.scaleId eq scaleId) and (ScaleSchedules.stallId eq stallId)
                }.any()
                if (!dup) {
                    ScaleSchedules.insert {
                        it[ScaleSchedules.scaleId] = scaleId; it[ScaleSchedules.stallId] = stallId
                        it[slot] = p["slot"] ?: "MORNING"; it[startHour] = startH; it[endHour] = endH
                    }
                }
                // 排班摊位达到 2 个 → 自动标记为多人共用
                val n = ScaleSchedules.selectAll().where { ScaleSchedules.scaleId eq scaleId }
                    .map { it[ScaleSchedules.stallId] }.distinct().size
                if (n >= 2) Scales.update({ Scales.id eq scaleId }) { it[shared] = true }
            }
            call.respondRedirect("/admin/sharing?msg=" + enc("排班已登记"))
        }

        post("/sharing/schedules/{id}/delete") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction {
                val pred = with(org.jetbrains.exposed.sql.SqlExpressionBuilder) { ScaleSchedules.id eq id }
                ScaleSchedules.deleteWhere { pred }
            }
            call.respondRedirect("/admin/sharing?msg=" + enc("排班已删除"))
        }

        post("/sharing/codes/new") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val p = call.receiveParameters()
            val stallId = p["stallId"]?.toIntOrNull() ?: 0
            val prefix = (p["prefix"] ?: "").trim()
            if (stallId == 0 || prefix.isBlank()) {
                call.respondRedirect("/admin/sharing?msg=" + enc("收款码参数不完整")); return@post
            }
            transaction {
                PaymentCodes.insert {
                    it[PaymentCodes.stallId] = stallId; it[PaymentCodes.prefix] = prefix
                    it[channel] = p["channel"] ?: "WECHAT"
                }
            }
            call.respondRedirect("/admin/sharing?msg=" + enc("收款码已登记"))
        }

        post("/sharing/codes/{id}/delete") {
            call.requireRole(Roles.MARKET_ADMIN) ?: return@post
            val id = call.parameters["id"]!!.toInt()
            transaction {
                val pred = with(org.jetbrains.exposed.sql.SqlExpressionBuilder) { PaymentCodes.id eq id }
                PaymentCodes.deleteWhere { pred }
            }
            call.respondRedirect("/admin/sharing?msg=" + enc("收款码已删除"))
        }
    }
}

private data class SharingRow(val id: Int, val device: String, val stall: String, val slot: String, val startH: Int, val endH: Int)
private data class PayCodeRow(val id: Int, val stall: String, val prefix: String, val channel: String)

data class ScaleRow(
    val id: Int, val deviceNo: String, val market: String, val stall: String, val category: String,
    val certNo: String, val certUntil: String, val online: Boolean, val status: String,
    val shared: Boolean, val seal: String?
)

data class StallRow(val id: Int, val market: String, val no: String, val category: String, val vendor: String, val credit: Int, val penalties: Int)

fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
