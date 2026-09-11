package city.market.web

import city.market.auth.Passwords
import city.market.auth.Roles
import city.market.auth.UserSession
import city.market.db.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.authRoutes() {
    get("/") {
        val s = call.sessionOrNull()
        if (s == null) call.respondRedirect("/login") else call.respondRedirect(
            when (s.role) {
                Roles.MARKET_ADMIN -> "/admin/scales"
                Roles.REGULATOR -> "/reg/dashboard"
                Roles.VENDOR -> "/vendor/home"
                else -> "/consumer/complaints"
            }
        )
    }

    get("/login") {
        call.respondHtml {
            page("登录", call.sessionOrNull()) {
                msgBox(call.request.queryParameters["msg"])
                div("card") {
                    h1 { +"用户登录" }
                    form(method = FormMethod.post, action = "/login") {
                        label { +"用户名" }; textInput { name = "username" }
                        label { +"密码" }; passwordInput { name = "password" }
                        button(classes = "btn", type = ButtonType.submit) { +"登录" }
                    }
                    p("muted") {
                        +"演示账号：监管 regulator/gov123 ｜ 市场管理 admin/market123 ｜ 摊主 vendor1/vendor123 ｜ 消费者 consumer/consumer123"
                    }
                }
            }
        }
    }

    post("/login") {
        val p = call.receiveParameters()
        val username = p["username"]?.trim().orEmpty()
        val password = p["password"].orEmpty()
        val user = transaction {
            Users.selectAll().where { Users.username eq username }.singleOrNull()
        }
        if (user == null || !Passwords.verify(password, user[Users.passwordHash])) {
            call.respondRedirect("/login?msg=" + java.net.URLEncoder.encode("用户名或密码错误", "UTF-8"))
            return@post
        }
        call.sessions.set(UserSession(user[Users.id], user[Users.username], user[Users.role], user[Users.displayName]))
        call.respondRedirect("/")
    }

    get("/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect("/login")
    }

    // 公示栏（公开）
    get("/disclosures") {
        val rows = transaction {
            (Disclosures innerJoin Markets).selectAll()
                .orderBy(Disclosures.publishedAt, SortOrder.DESC).limit(100)
                .map {
                    DisclosureView(
                        it[Disclosures.id], it[Markets.name], it[Disclosures.title],
                        it[Disclosures.content], it[Disclosures.status], it[Disclosures.publishedAt].toString()
                    )
                }
        }
        call.respondHtml {
            page("公示栏", call.sessionOrNull()) {
                div("card") {
                    h1 { +"短斤少两治理公示栏" }
                    if (rows.isEmpty()) p("muted") { +"暂无公示" }
                    rows.forEach { d ->
                        div {
                            badge(if (d.status == "PUBLISHED") "公示中" else "已撤回", if (d.status == "PUBLISHED") "b-green" else "")
                            h2 { +"${d.title}" }
                            p { +d.content }
                            p("muted") { +"${d.market} ｜ 公示时间：${d.time.take(16)}" }
                            hr {}
                        }
                    }
                }
            }
        }
    }
}

data class DisclosureView(val id: Int, val market: String, val title: String, val content: String, val status: String, val time: String)
