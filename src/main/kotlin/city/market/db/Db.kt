package city.market.db

import city.market.auth.Passwords
import city.market.auth.Roles
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

object Db {
    lateinit var uploadDir: String
        private set

    fun init() {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${env("DB_HOST", "db")}:${env("DB_PORT", "5432")}/${env("DB_NAME", "scaleguard")}"
            username = env("DB_USER", "scaleguard")
            password = env("DB_PASSWORD", "scaleguard")
            maximumPoolSize = 8
        }
        Database.connect(HikariDataSource(cfg))
        uploadDir = env("UPLOAD_DIR", "uploads")
        File(uploadDir).mkdirs()

        transaction {
            SchemaUtils.create(*allTables)
            // 轻量迁移：为已存在的表补充新增列（如 transactions 的补传字段）
            SchemaUtils.addMissingColumnsStatements(*allTables).forEach { exec(it) }
        }
        if (env("SEED_DEMO", "true") == "true") seedIfEmpty()
    }

    private fun env(k: String, d: String) = System.getenv(k) ?: d

    fun logEvent(scaleId: Int, type: String, detail: String, at: LocalDateTime = LocalDateTime.now()) {
        ScaleEvents.insert {
            it[ScaleEvents.scaleId] = scaleId
            it[eventType] = type
            it[ScaleEvents.detail] = detail
            it[createdAt] = at
        }
    }

    private fun seedIfEmpty() {
        val hasData = transaction { Users.selectAll().limit(1).any() }
        if (hasData) return
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val rnd = Random(42)

        transaction {
            // ---- 市场 ----
            val m1 = Markets.insert { it[name] = "城东农贸市场"; it[address] = "城东路 88 号"; it[creditScore] = 95 } get Markets.id
            val m2 = Markets.insert { it[name] = "城西生鲜市场"; it[address] = "城西大道 12 号" } get Markets.id

            // ---- 用户 ----
            fun user(u: String, p: String, role: String, name: String) = Users.insert {
                it[username] = u; it[passwordHash] = Passwords.hash(p); it[Users.role] = role; it[displayName] = name
            } get Users.id
            user("admin", "market123", Roles.MARKET_ADMIN, "市场管理员-陈立")
            val regulatorId = user("regulator", "gov123", Roles.REGULATOR, "监管员-赵敏")
            val v1 = user("vendor1", "vendor123", Roles.VENDOR, "摊主-张老三")
            val v2 = user("vendor2", "vendor123", Roles.VENDOR, "摊主-李秀兰")
            val v3 = user("vendor3", "vendor123", Roles.VENDOR, "摊主-王铁柱")
            val c1 = user("consumer", "consumer123", Roles.CONSUMER, "消费者-王芳")
            val c2 = user("consumer2", "consumer123", Roles.CONSUMER, "消费者-刘洋")

            // ---- 摊位 ----
            fun stall(market: Int, no: String, vendor: Int, cat: String, credit: Int = 100, penalties: Int = 0) =
                Stalls.insert {
                    it[marketId] = market; it[stallNo] = no; it[vendorId] = vendor; it[category] = cat
                    it[creditScore] = credit; it[penaltyCount] = penalties
                } get Stalls.id
            val s1 = stall(m1, "A-01", v1, "肉类")
            val s2 = stall(m1, "A-02", v2, "水产", credit = 90, penalties = 1)
            val s3 = stall(m1, "A-03", v3, "蔬菜")
            val s4 = stall(m2, "B-01", v1, "水果")
            val s5 = stall(m2, "B-02", v2, "禽蛋")
            val s6 = stall(m2, "B-03", v3, "豆制品")

            // ---- 电子秤 ----
            fun scalePhoto(dev: String): String {
                val f = File(uploadDir, "seal-$dev.svg")
                if (!f.exists()) f.writeText(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200"><rect width="320" height="200" fill="#f5f0e6"/><rect x="20" y="20" width="280" height="160" fill="none" stroke="#b03030" stroke-width="4"/><text x="160" y="90" font-size="28" text-anchor="middle" fill="#b03030">检定封签</text><text x="160" y="130" font-size="20" text-anchor="middle" fill="#333">$dev</text></svg>"""
                )
                return f.name
            }
            fun scale(
                dev: String, stallId: Int, cat: String, certDays: Long, online: Boolean = true,
                status: String = "ACTIVE", shared: Boolean = false, boundDaysAgo: Long = 200
            ) = Scales.insert {
                it[deviceNo] = dev; it[Scales.stallId] = stallId; it[category] = cat
                it[certNo] = "检字2025-$dev"; it[certValidUntil] = today.plusDays(certDays)
                it[sealPhoto] = scalePhoto(dev); it[Scales.online] = online
                it[Scales.status] = status; it[Scales.shared] = shared
                it[boundAt] = now.minusDays(boundDaysAgo); it[registeredAt] = now.minusDays(boundDaysAgo)
            } get Scales.id

            val k1 = scale("DEV-0001", s1, "肉类", 200)
            val k2 = scale("DEV-0002", s2, "水产", -10, status = "SUSPENDED")          // 证书过期 + 已停用
            val k3 = scale("DEV-0003", s3, "蔬菜", 15, online = false)                 // 即将到期 + 离线
            val k4 = scale("DEV-0004", s4, "水果", 300, shared = true)                 // 多人共用
            val k5 = scale("DEV-0005", s5, "禽蛋", 180, boundDaysAgo = 3)              // 3 天前临时换秤
            val k6 = scale("DEV-0006", s6, "豆制品", 90)
            val scaleIds = listOf(k1, k2, k3, k4, k5, k6)
            val stallOf = mapOf(k1 to s1, k2 to s2, k3 to s3, k4 to s4, k5 to s5, k6 to s6)

            scaleIds.forEach { id -> logEvent(id, "REGISTERED", "设备登记入库，绑定摊位", now.minusDays(200)) }
            logEvent(k2, "SUSPENDED", "抽检误差超标，设备停用", now.minusDays(20))
            logEvent(k3, "OFFLINE", "设备离线，交易离线缓存", now.minusDays(2))
            logEvent(k5, "REGISTERED", "摊位临时更换电子秤", now.minusDays(3))

            // ---- 交易流水（近 60 天） ----
            val unitPrice = mapOf(k1 to 36.0, k2 to 60.0, k3 to 8.0, k4 to 12.0, k5 to 16.0, k6 to 6.0)
            for (k in scaleIds) {
                val daily = if (k == k1 || k == k4) 90 + rnd.nextInt(60) else 20 + rnd.nextInt(30)
                for (d in 0 until 60) {
                    val n = if (d < 30) daily else daily / 2
                    repeat(n) {
                        val w = 200 + rnd.nextInt(4800)
                        Transactions.insert {
                            it[scaleId] = k; it[stallId] = stallOf.getValue(k)
                            it[ts] = now.minusDays(d.toLong()).withHour(6 + rnd.nextInt(12)).withMinute(rnd.nextInt(60))
                            it[weightG] = w
                            it[amountYuan] = BigDecimal(w / 500.0 * unitPrice.getValue(k)).setScale(2, java.math.RoundingMode.HALF_UP)
                            it[offline] = (k == k3 && d < 2)
                        }
                    }
                }
            }

            // ---- 历史抽检（合格） ----
            fun passInspection(scaleId: Int, daysAgo: Long) {
                val displayed = 1000 + rnd.nextInt(-2, 3)
                Inspections.insert {
                    it[Inspections.scaleId] = scaleId; it[inspectorId] = regulatorId
                    it[standardWeightG] = 1000; it[displayedWeightG] = displayed
                    it[errorG] = displayed - 1000
                    it[errorPct] = BigDecimal((displayed - 1000) * 100.0 / 1000).setScale(4, java.math.RoundingMode.HALF_UP)
                    it[sealStatus] = "INTACT"; it[vendorConfirmed] = true
                    it[result] = "PASS"; it[kind] = "SPOT"; it[createdAt] = now.minusDays(daysAgo)
                }
                logEvent(scaleId, "INSPECTION_PASS", "例行抽检合格", now.minusDays(daysAgo))
            }
            passInspection(k1, 45); passInspection(k4, 30); passInspection(k6, 25)

            // ---- 超标抽检 → 已确认处罚 → 公示 → 整改待复检 ----
            val i1 = Inspections.insert {
                it[taskId] = null; it[scaleId] = k2; it[inspectorId] = regulatorId
                it[standardWeightG] = 1000; it[displayedWeightG] = 1052
                it[errorG] = 52; it[errorPct] = BigDecimal("5.2000")
                it[sealStatus] = "BROKEN"; it[vendorConfirmed] = true
                it[result] = "OVER_ERROR"; it[kind] = "SPOT"; it[createdAt] = now.minusDays(20)
            } get Inspections.id
            logEvent(k2, "INSPECTION_OVER", "抽检误差 +52g（5.2%），超过允许误差", now.minusDays(20))

            val p1 = Penalties.insert {
                it[stallId] = s2; it[scaleId] = k2; it[inspectionId] = i1; it[complaintId] = null
                it[amount] = BigDecimal("1000.00"); it[reason] = "抽检误差超标（+5.2%）且封签破损"
                it[status] = "CONFIRMED"; it[issuedBy] = regulatorId
                it[createdAt] = now.minusDays(20); it[confirmedAt] = now.minusDays(19)
            } get Penalties.id
            logEvent(k2, "PENALTY_CONFIRMED", "处罚确认：罚款 1000 元", now.minusDays(19))
            Disclosures.insert {
                it[marketId] = m1; it[stallId] = s2; it[penaltyId] = p1
                it[title] = "A-02 水产摊位短斤少两处罚公示"
                it[content] = "A-02 摊位电子秤 DEV-0002 抽检误差 +5.2% 超标，封签破损，处以罚款 1000 元，设备停用整改。"
                it[publishedAt] = now.minusDays(19)
            }
            Rectifications.insert {
                it[penaltyId] = p1; it[stallId] = s2; it[scaleId] = k2
                it[description] = "已送计量所重新校准，更换新封签，申请复检。"
                it[createdAt] = now.minusDays(2)
            }
            InspectionTasks.insert {
                it[scaleId] = k2; it[stallId] = s2
                it[reason] = "整改完成，申请复检"; it[score] = 0
                it[generatedBy] = "REINSPECT"; it[createdAt] = now.minusDays(2)
            }

            // ---- 投诉 ----
            // c1：已核验（购买时证书已过期 → 交易不可信标记），已生成待确认处罚
            val comp1 = Complaints.insert {
                it[consumerId] = c1; it[stallId] = s2; it[scaleId] = k2
                it[purchaseTime] = now.minusDays(10); it[product] = "鲈鱼"
                it[nominalWeightG] = 1000; it[reweighedWeightG] = 920
                it[paymentRef] = "WX2026090100001"; it[status] = "VERIFIED"
                it[trustFlags] = "CERT_EXPIRED"; it[shortfallG] = 80
                it[createdAt] = now.minusDays(10)
            } get Complaints.id
            Penalties.insert {
                it[stallId] = s2; it[scaleId] = k2; it[inspectionId] = null; it[complaintId] = comp1
                it[amount] = BigDecimal("500.00"); it[reason] = "投诉核验属实：标称 1000g 复称 920g，短少 80g"
                it[status] = "ISSUED"; it[issuedBy] = regulatorId; it[createdAt] = now.minusDays(9)
            }
            logEvent(k2, "COMPLAINT_VERIFIED", "投诉核验属实：短少 80g", now.minusDays(9))
            logEvent(k2, "PENALTY_ISSUED", "投诉处罚开出：罚款 500 元", now.minusDays(9))

            // c2：待核验
            Complaints.insert {
                it[consumerId] = c2; it[stallId] = s4; it[scaleId] = k4
                it[purchaseTime] = now.minusDays(5); it[product] = "苹果"
                it[nominalWeightG] = 2000; it[reweighedWeightG] = 1910
                it[paymentRef] = "ZFB2026090600002"; it[status] = "SUBMITTED"
                it[trustFlags] = "SHARED_SCALE"; it[shortfallG] = 90
                it[createdAt] = now.minusDays(5)
            }

            // c3：历史已办结 + 回访（用于“处罚后变化”对比：处罚前）
            val comp3 = Complaints.insert {
                it[consumerId] = c1; it[stallId] = s2; it[scaleId] = k2
                it[purchaseTime] = now.minusDays(40); it[product] = "基围虾"
                it[nominalWeightG] = 500; it[reweighedWeightG] = 470
                it[paymentRef] = "WX2025080200003"; it[status] = "FOLLOWED_UP"
                it[trustFlags] = ""; it[shortfallG] = 30
                it[createdAt] = now.minusDays(40)
            } get Complaints.id
            FollowUps.insert {
                it[complaintId] = comp3; it[result] = "摊主退赔差价，消费者满意"; it[satisfied] = true
                it[createdAt] = now.minusDays(38)
            }
        }
    }
}
