package city.market.web

import city.market.auth.Roles
import city.market.auth.UserSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.html.*

fun ApplicationCall.sessionOrNull(): UserSession? = sessions.get<UserSession>()

/** 要求登录且角色匹配；不满足时直接响应并重定向/403，返回 null */
suspend fun ApplicationCall.requireRole(vararg roles: String): UserSession? {
    val s = sessions.get<UserSession>()
    if (s == null) {
        respondRedirect("/login")
        return null
    }
    if (roles.isNotEmpty() && s.role !in roles) {
        respondText("无权限访问（需要 ${roles.joinToString("/") { Roles.label(it) }}）", status = HttpStatusCode.Forbidden)
        return null
    }
    return s
}

// ---------- 状态中文标签 ----------
object Labels {
    fun task(s: String) = mapOf("PENDING" to "待办", "DONE" to "已完成", "CANCELLED" to "已取消")[s] ?: s
    fun result(s: String) = mapOf("PASS" to "合格", "OVER_ERROR" to "误差超标")[s] ?: s
    fun seal(s: String) = mapOf("INTACT" to "完好", "BROKEN" to "破损", "REPLACED" to "已更换")[s] ?: s
    fun complaint(s: String) = mapOf(
        "SUBMITTED" to "待核验", "RESP_PENDING" to "责任认定中", "VERIFIED" to "已核验", "REJECTED" to "已驳回",
        "RESOLVED" to "已办结", "FOLLOWED_UP" to "已回访"
    )[s] ?: s
    fun penaltyKind(s: String) = if (s == "DEVICE") "设备管理责任" else "经营短斤责任"
    fun respStatus(s: String) = mapOf("PENDING" to "待认定", "CONFIRMED" to "已认定拆分", "CANCELLED" to "已撤销")[s] ?: s
    fun penalty(s: String) = mapOf(
        "ISSUED" to "已开出", "APPEALING" to "申诉中", "CONFIRMED" to "已确认",
        "RECTIFYING" to "整改中", "RECTIFIED" to "已整改", "CLOSED" to "已结案", "CANCELLED" to "已撤销"
    )[s] ?: s
    fun scale(s: String) = mapOf("ACTIVE" to "在用", "SUSPENDED" to "停用", "RETIRED" to "报废")[s] ?: s
    fun rectification(s: String) = mapOf(
        "SUBMITTED" to "已提交待复检", "REINSPECT_PASSED" to "复检通过", "CLOSED" to "已闭环"
    )[s] ?: s
    fun appeal(s: String) = mapOf("PENDING" to "待处理", "ACCEPTED" to "申诉成立", "REJECTED" to "申诉驳回")[s] ?: s
    fun disclosure(s: String) = mapOf("PUBLISHED" to "公示中", "WITHDRAWN" to "已撤回")[s] ?: s
    fun event(s: String) = mapOf(
        "REGISTERED" to "设备登记", "INSPECTION_PASS" to "抽检合格", "INSPECTION_OVER" to "抽检超标",
        "SUSPENDED" to "设备停用", "REACTIVATED" to "恢复使用", "COMPLAINT_VERIFIED" to "投诉属实",
        "PENALTY_ISSUED" to "处罚开出", "PENALTY_CONFIRMED" to "处罚确认", "RECTIFICATION" to "提交整改",
        "REINSPECT_PASS" to "复检通过", "SEAL_CHANGED" to "封签更换", "FOLLOW_UP" to "投诉回访",
        "OFFLINE" to "设备离线", "ONLINE" to "恢复联网",
        "OFFLINE_SYNC" to "离线补传", "OFFLINE_ANOMALY" to "离线异常",
        "MANUAL_REVIEW" to "人工复核", "REINSPECT_SUGGESTED" to "建议补做抽检",
        "RESP_PENDING" to "责任待认定", "RESP_SPLIT" to "责任拆分认定"
    )[s] ?: s
}

fun statusBadgeClass(status: String) = when (status) {
    "OVER_ERROR", "SUSPENDED", "BROKEN", "ISSUED", "APPEALING", "SUBMITTED", "RECTIFYING" -> "b-red"
    "PASS", "ACTIVE", "RESOLVED", "FOLLOWED_UP", "RECTIFIED", "CLOSED", "PUBLISHED", "CONFIRMED", "VERIFIED" -> "b-green"
    else -> "b-yellow"
}

fun FlowContent.badge(text: String, cls: String = "") {
    span("badge $cls") { +text }
}

fun FlowContent.msgBox(msg: String?) {
    if (!msg.isNullOrBlank()) div("msg") { +msg }
}

fun HTML.page(title: String, session: UserSession?, content: DIV.() -> Unit) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
        title { +"$title · 电子秤监管服务" }
        style {
            unsafe {
                +"""
                *{box-sizing:border-box}body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f4f6f8;color:#222}
                nav{background:#1a5f2a;padding:10px 20px;display:flex;gap:14px;align-items:center;flex-wrap:wrap}
                nav a{color:#dff3e2;text-decoration:none;font-size:14px}nav a:hover{color:#fff;text-decoration:underline}
                nav .brand{font-weight:700;font-size:16px;margin-right:12px;color:#fff}
                nav .who{margin-left:auto;font-size:13px;color:#bfe6c6}
                main{max-width:1100px;margin:20px auto;padding:0 16px}
                .card{background:#fff;border-radius:8px;padding:18px 20px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
                h1{font-size:20px;margin:0 0 12px}h2{font-size:16px;margin:0 0 10px;color:#1a5f2a}
                table{border-collapse:collapse;width:100%;font-size:13px;margin-top:8px}
                th,td{border:1px solid #e2e6ea;padding:6px 8px;text-align:left;vertical-align:top}
                th{background:#eef4ee}
                .badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;background:#e8ecef;margin:1px}
                .b-red{background:#fde2e2;color:#a11}.b-green{background:#dff3e2;color:#175}.b-yellow{background:#fff3cd;color:#854}
                .btn{display:inline-block;background:#1a5f2a;color:#fff;border:none;border-radius:5px;padding:6px 14px;font-size:13px;cursor:pointer;text-decoration:none}
                .btn.sm{padding:2px 8px;font-size:12px}.btn.gray{background:#6c757d}
                input,select,textarea{padding:6px 8px;border:1px solid #c9d1d9;border-radius:5px;font-size:13px;margin:2px 0 10px;max-width:100%}
                input[type=text],input[type=password],input[type=number],input[type=date],input[type=datetime-local],select{width:100%}
                input[type=checkbox]{width:auto}
                label{font-size:13px;color:#444;display:block;margin-top:4px}
                .msg{background:#e6f4ea;border:1px solid #b7dfc0;padding:8px 12px;border-radius:6px;margin-bottom:12px;font-size:14px}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:16px}
                .stat{background:#fff;border-radius:8px;padding:12px;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.08)}
                .stat .n{font-size:22px;font-weight:700;color:#1a5f2a}
                .stat .n.b-red{color:#a11;background:none;padding:0}
                .muted{color:#778;font-size:12px}
                form.inline{display:inline}
                pre.evidence{background:#f7f8f9;border:1px solid #e2e6ea;border-radius:6px;padding:10px;font-size:12px;white-space:pre-wrap;font-family:ui-monospace,Menlo,Consolas,monospace;margin:6px 0;line-height:1.5}
                """.trimIndent()
            }
        }
    }
    body {
        nav {
            a(href = "/") { span("brand") { +"⚖ 电子秤监管服务" } }
            if (session != null) {
                when (session.role) {
                    Roles.MARKET_ADMIN -> {
                        a(href = "/admin/scales") { +"电子秤台账" }
                        a(href = "/admin/sharing") { +"共用秤排班" }
                        a(href = "/admin/stalls") { +"摊位管理" }
                    }
                    Roles.REGULATOR -> {
                        a(href = "/reg/dashboard") { +"工作台" }
                        a(href = "/reg/tasks") { +"抽检任务" }
                        a(href = "/reg/complaints") { +"投诉核验" }
                        a(href = "/reg/responsibility") { +"责任认定" }
                        a(href = "/reg/penalties") { +"处罚管理" }
                        a(href = "/reg/offline") { +"离线复核" }
                        a(href = "/reg/disclosures") { +"公示管理" }
                        a(href = "/reg/reports/tracking") { +"变化追踪" }
                        a(href = "/reg/reports/annual") { +"年度治理" }
                    }
                    Roles.VENDOR -> {
                        a(href = "/vendor/home") { +"我的摊位" }
                    }
                    Roles.CONSUMER -> {
                        a(href = "/consumer/complaints") { +"我的投诉" }
                        a(href = "/consumer/complaints/new") { +"发起投诉" }
                    }
                }
                a(href = "/disclosures") { +"公示栏" }
                span("who") { +"${session.displayName}（${Roles.label(session.role)}）" }
                a(href = "/logout") { +"退出" }
            } else {
                a(href = "/disclosures") { +"公示栏" }
                span("who") { a(href = "/login") { +"登录" } }
            }
        }
        main {
            div {
                content()
            }
        }
    }
}
