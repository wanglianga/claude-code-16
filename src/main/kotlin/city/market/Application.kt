package city.market

import city.market.auth.UserSession
import city.market.db.Db
import city.market.web.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.utils.io.core.*
import java.io.File

fun main() {
    Db.init()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module() {
    val secret = (System.getenv("SESSION_SECRET") ?: "dev-secret-change-me-0123456789abcdef").toByteArray()
    install(Sessions) {
        cookie<UserSession>("scale_session") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 8 * 3600
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(secret))
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("请求处理失败", cause)
            call.respondText("服务器内部错误：${cause.message}", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        staticFiles("/uploads", File(Db.uploadDir))
        get("/health") {
            val dbOk = try {
                org.jetbrains.exposed.sql.transactions.transaction {
                    exec("SELECT 1")
                }; true
            } catch (e: Exception) { false }
            call.respondText(
                """{"status":"${if (dbOk) "ok" else "degraded"}","db":${dbOk}}""",
                ContentType.Application.Json
            )
        }
        authRoutes()
        adminRoutes()
        complaintRoutes()
        regulatorRoutes()
        vendorRoutes()
        reportRoutes()
        offlineRoutes()
    }
}

/** 保存 multipart 上传文件，返回相对文件名；无文件返回 null */
suspend fun MultiPartData.saveUpload(field: String): String? {
    var saved: String? = null
    while (true) {
        val part = readPart() ?: break
        if (part is PartData.FileItem && part.name == field && !part.originalFileName.isNullOrBlank()) {
            val ext = part.originalFileName!!.substringAfterLast('.', "jpg").replace(Regex("[^a-zA-Z0-9]"), "")
            val name = "${java.util.UUID.randomUUID()}.$ext"
            val bytes = part.provider().readBytes()
            if (bytes.size > 5 * 1024 * 1024) throw IllegalArgumentException("文件超过 5MB 限制")
            File(Db.uploadDir, name).writeBytes(bytes)
            saved = name
        }
        part.dispose()
    }
    return saved
}
