package city.market.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// ---------- 用户与组织 ----------
object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val role = varchar("role", 20) // MARKET_ADMIN / REGULATOR / VENDOR / CONSUMER
    val displayName = varchar("display_name", 64)
    override val primaryKey = PrimaryKey(id)
}

object Markets : Table("markets") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)
    val address = varchar("address", 200)
    val creditScore = integer("credit_score").default(100)       // 市场信用分
    val inspectionLevel = varchar("inspection_level", 10).default("NORMAL") // LOW/NORMAL/HIGH 抽检频次
    override val primaryKey = PrimaryKey(id)
}

object Stalls : Table("stalls") {
    val id = integer("id").autoIncrement()
    val marketId = integer("market_id").references(Markets.id)
    val stallNo = varchar("stall_no", 20)
    val vendorId = integer("vendor_id").references(Users.id).nullable()
    val category = varchar("category", 50) // 经营品类
    val creditScore = integer("credit_score").default(100)
    val penaltyCount = integer("penalty_count").default(0)
    override val primaryKey = PrimaryKey(id)
}

// ---------- 电子秤 ----------
object Scales : Table("scales") {
    val id = integer("id").autoIncrement()
    val deviceNo = varchar("device_no", 50).uniqueIndex() // 设备编号
    val stallId = integer("stall_id").references(Stalls.id)
    val category = varchar("category", 50)                // 经营品类
    val certNo = varchar("cert_no", 64)                   // 检定证书号
    val certValidUntil = date("cert_valid_until")         // 证书有效期
    val sealPhoto = varchar("seal_photo", 255).nullable() // 封签照片
    val online = bool("online").default(true)             // 联网状态
    val status = varchar("status", 20).default("ACTIVE")  // ACTIVE / SUSPENDED / RETIRED
    val shared = bool("shared").default(false)            // 是否多人共用
    val boundAt = datetime("bound_at")                    // 绑定到当前摊位的时间（换秤检测用）
    val registeredAt = datetime("registered_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 交易流水（用于投诉匹配与峰值分析） ----------
object Transactions : Table("transactions") {
    val id = long("id").autoIncrement()
    val scaleId = integer("scale_id").references(Scales.id)
    val stallId = integer("stall_id").references(Stalls.id)
    val ts = datetime("ts")
    val weightG = integer("weight_g")
    val amountYuan = decimal("amount", 10, 2)
    val offline = bool("offline").default(false) // 离线补传交易
    val syncBatchId = integer("sync_batch_id").nullable()  // 补传批次号
    val trust = varchar("trust", 20).default("NORMAL")     // NORMAL/TRUSTED/PENDING_REVIEW/REVIEWED_OK/SUSPICIOUS
    val trustFlags = varchar("trust_flags", 255).nullable() // 可信度标记，逗号分隔
    override val primaryKey = PrimaryKey(id)
}

// ---------- 离线交易补传批次 ----------
object OfflineSyncs : Table("offline_syncs") {
    val id = integer("id").autoIncrement()
    val scaleId = integer("scale_id").references(Scales.id)
    val stallId = integer("stall_id").references(Stalls.id)
    val uploadedBy = integer("uploaded_by").references(Users.id)
    val offlineStart = datetime("offline_start")       // 离线开始
    val offlineEnd = datetime("offline_end")           // 恢复网络时间
    val deviceClock = datetime("device_clock").nullable() // 设备本地时钟读数
    val clockDriftMin = integer("clock_drift_min").default(0) // 与服务器时间漂移（分钟）
    val sealAtSync = varchar("seal_at_sync", 20).default("INTACT") // 补传时封签状态
    val txUploaded = integer("tx_uploaded").default(0)      // 上传笔数
    val txAccepted = integer("tx_accepted").default(0)      // 入库笔数
    val txDuplicate = integer("tx_duplicate").default(0)    // 与服务器已有流水重复
    val txInvalid = integer("tx_invalid").default(0)        // 时间不在离线窗口内
    val txReevaluated = integer("tx_reevaluated").default(0) // 窗口内重估总笔数
    val pendingReview = integer("pending_review").default(0) // 进入人工复核笔数
    val peakRatio = integer("peak_ratio").default(0)         // 离线窗口高峰期占比 %
    val anomalies = varchar("anomalies", 500).default("")    // PEAK_CONCENTRATED/CLOCK_DRIFT/SEAL_BROKEN/BINDING_CHANGED
    val complaintsInWindow = integer("complaints_in_window").default(0) // 离线期间投诉数
    val needReinspection = bool("need_reinspection").default(false)   // 是否建议补做抽检
    val status = varchar("status", 20).default("SYNCED")     // SYNCED / REVIEWED / FLAGGED
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 抽检任务与抽检记录 ----------
object InspectionTasks : Table("inspection_tasks") {
    val id = integer("id").autoIncrement()
    val scaleId = integer("scale_id").references(Scales.id)
    val stallId = integer("stall_id").references(Stalls.id)
    val reason = varchar("reason", 500)          // 生成原因（评分因子）
    val score = integer("score")                 // 风险评分
    val status = varchar("status", 20).default("PENDING") // PENDING / DONE / CANCELLED
    val generatedBy = varchar("generated_by", 12).default("RULE") // RULE / MANUAL / REINSPECT
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Inspections : Table("inspections") {
    val id = integer("id").autoIncrement()
    val taskId = integer("task_id").references(InspectionTasks.id).nullable()
    val scaleId = integer("scale_id").references(Scales.id)
    val inspectorId = integer("inspector_id").references(Users.id)
    val standardWeightG = integer("standard_weight_g") // 标准砝码重量
    val displayedWeightG = integer("displayed_weight_g") // 秤显示值
    val errorG = integer("error_g")                    // 误差 = 显示 - 标准
    val errorPct = decimal("error_pct", 8, 4)          // 误差百分比
    val sealStatus = varchar("seal_status", 20)        // INTACT / BROKEN / REPLACED
    val vendorConfirmed = bool("vendor_confirmed").default(false) // 摊主确认
    val photos = varchar("photos", 500).nullable()     // 现场照片，逗号分隔
    val result = varchar("result", 20)                 // PASS / OVER_ERROR
    val kind = varchar("kind", 20).default("SPOT")     // SPOT / REINSPECT
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 消费者投诉 ----------
object Complaints : Table("complaints") {
    val id = integer("id").autoIncrement()
    val consumerId = integer("consumer_id").references(Users.id)
    val stallId = integer("stall_id").references(Stalls.id)
    val scaleId = integer("scale_id").references(Scales.id).nullable() // 匹配到的秤
    val purchaseTime = datetime("purchase_time")
    val product = varchar("product", 100)
    val nominalWeightG = integer("nominal_weight_g")   // 标称重量
    val reweighedWeightG = integer("reweighed_weight_g") // 复称重量
    val paymentRef = varchar("payment_ref", 100).nullable() // 付款记录单号
    val photos = varchar("photos", 500).nullable()
    val status = varchar("status", 20).default("SUBMITTED") // SUBMITTED/VERIFIED/REJECTED/RESOLVED/FOLLOWED_UP
    val trustFlags = varchar("trust_flags", 500).nullable() // 交易可信度标记
    val matchedTxId = long("matched_tx_id").nullable()      // 匹配到的当天交易
    val shortfallG = integer("shortfall_g").nullable()      // 短少量
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 处罚 / 申诉 / 整改 / 回访 ----------
object Penalties : Table("penalties") {
    val id = integer("id").autoIncrement()
    val stallId = integer("stall_id").references(Stalls.id)
    val scaleId = integer("scale_id").references(Scales.id).nullable()
    val inspectionId = integer("inspection_id").references(Inspections.id).nullable()
    val complaintId = integer("complaint_id").references(Complaints.id).nullable()
    val amount = decimal("amount", 10, 2) // 罚款金额
    val reason = varchar("reason", 500)
    val status = varchar("status", 20).default("ISSUED")
    // ISSUED / APPEALING / CONFIRMED / RECTIFYING / RECTIFIED / CLOSED / CANCELLED
    val issuedBy = integer("issued_by").references(Users.id)
    val createdAt = datetime("created_at")
    val confirmedAt = datetime("confirmed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Appeals : Table("appeals") {
    val id = integer("id").autoIncrement()
    val penaltyId = integer("penalty_id").references(Penalties.id)
    val vendorId = integer("vendor_id").references(Users.id)
    val content = text("content")
    val status = varchar("status", 20).default("PENDING") // PENDING / ACCEPTED / REJECTED
    val resolution = text("resolution").nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Rectifications : Table("rectifications") {
    val id = integer("id").autoIncrement()
    val penaltyId = integer("penalty_id").references(Penalties.id)
    val stallId = integer("stall_id").references(Stalls.id)
    val scaleId = integer("scale_id").references(Scales.id).nullable()
    val description = text("description")
    val status = varchar("status", 20).default("SUBMITTED") // SUBMITTED / REINSPECT_PASSED / CLOSED
    val createdAt = datetime("created_at")
    val completedAt = datetime("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FollowUps : Table("follow_ups") { // 投诉回访
    val id = integer("id").autoIncrement()
    val complaintId = integer("complaint_id").references(Complaints.id)
    val result = varchar("result", 255)
    val satisfied = bool("satisfied")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 公示 ----------
object Disclosures : Table("disclosures") {
    val id = integer("id").autoIncrement()
    val marketId = integer("market_id").references(Markets.id)
    val stallId = integer("stall_id").references(Stalls.id).nullable()
    val penaltyId = integer("penalty_id").references(Penalties.id).nullable()
    val title = varchar("title", 200)
    val content = text("content")
    val status = varchar("status", 20).default("PUBLISHED") // PUBLISHED / WITHDRAWN
    val publishedAt = datetime("published_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 秤监管档案（统一时间线） ----------
object ScaleEvents : Table("scale_events") {
    val id = long("id").autoIncrement()
    val scaleId = integer("scale_id").references(Scales.id)
    val eventType = varchar("event_type", 40)
    // REGISTERED / INSPECTION_PASS / INSPECTION_OVER / SUSPENDED / REACTIVATED /
    // COMPLAINT_VERIFIED / PENALTY_ISSUED / PENALTY_CONFIRMED / RECTIFICATION /
    // REINSPECT_PASS / SEAL_CHANGED / FOLLOW_UP / CERT_UPDATED / OFFLINE / ONLINE
    val detail = text("detail")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

// ---------- 封签更换记录 ----------
object SealChanges : Table("seal_changes") {
    val id = integer("id").autoIncrement()
    val scaleId = integer("scale_id").references(Scales.id)
    val oldSealPhoto = varchar("old_seal_photo", 255).nullable()
    val newSealPhoto = varchar("new_seal_photo", 255).nullable()
    val reason = varchar("reason", 255)
    val changedBy = integer("changed_by").references(Users.id)
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

val allTables = arrayOf(
    Users, Markets, Stalls, Scales, Transactions, OfflineSyncs, InspectionTasks, Inspections,
    Complaints, Penalties, Appeals, Rectifications, FollowUps, Disclosures,
    ScaleEvents, SealChanges
)
