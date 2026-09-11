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
                                    td { a(href = "/reg/scales/${r.id}") { +"监管档案" } }
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
    }
}

data class ScaleRow(
    val id: Int, val deviceNo: String, val market: String, val stall: String, val category: String,
    val certNo: String, val certUntil: String, val online: Boolean, val status: String,
    val shared: Boolean, val seal: String?
)

data class StallRow(val id: Int, val market: String, val no: String, val category: String, val vendor: String, val credit: Int, val penalties: Int)

fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
