package works.alomair.finance

import android.content.Context
import androidx.room.*
import java.util.UUID

@Entity(tableName = "categories")
data class Category(@PrimaryKey val key: String, val labelEn: String, val labelAr: String, val hidden: Boolean = false)

object QueueStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_CONFIG = "FAILED_CONFIG"
    const val DONE = "DONE"
    const val DISMISSED = "DISMISSED"
}

@Entity(tableName = "pending_messages", indices = [Index(value = ["bodyHash"], unique = true)])
data class PendingMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sender: String, val body: String, val receivedAt: Long, val bodyHash: String,
    val queuedProviderId: String,
    val status: String = QueueStatus.PENDING, val attempts: Int = 0,
    val malformedAttempts: Int = 0, val lastErrorCode: String? = null,
    val extractedJson: String? = null, val leaseUntil: Long = 0,
    val nextAttemptAt: Long = 0,
) {
    override fun toString() = "PendingMessage(id=$id,status=$status)"
}

@Entity(tableName = "transactions", indices = [
    Index(value = ["bank", "reference"], unique = true),
    Index(value = ["sourceMessageId"], unique = true), Index("categoryKey"),
], foreignKeys = [
    ForeignKey(entity = Category::class, parentColumns = ["key"], childColumns = ["categoryKey"]),
    ForeignKey(entity = PendingMessage::class, parentColumns = ["id"], childColumns = ["sourceMessageId"]),
])
data class FinanceTransaction(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String, val direction: String, val amountMinor: Long, val currency: String,
    val occurredAt: Long, val zone: String = "Asia/Riyadh",
    val merchant: String = "", val bank: String = "", val accountLabel: String = "",
    val categoryKey: String = "other", val reference: String? = null,
    val source: String = "manual", val sourceMessageId: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
) {
    init {
        UUID.fromString(id)
        require(type in setOf("income", "expense", "transfer")) { "TYPE" }
        require(direction in setOf("in", "out")) { "DIRECTION" }
        require(type != "income" || direction == "in") { "DIRECTION" }
        require(type != "expense" || direction == "out") { "DIRECTION" }
        require(amountMinor > 0) { "AMOUNT" }
        Money.exponent(currency)
        java.time.ZoneId.of(zone)
        require(source in setOf("manual", "sms")) { "SOURCE" }
        require(reference == null || reference.isNotBlank()) { "REFERENCE" }
    }
    override fun toString() = "FinanceTransaction(id=$id)"
}

@Entity(tableName = "processing_stats")
data class ProcessingStats(@PrimaryKey val id: Int = 1, val ignored: Long = 0, val lastProcessedAt: Long? = null)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC") fun transactions(): List<FinanceTransaction>
    @Query("SELECT * FROM transactions WHERE id=:id") fun transaction(id: String): FinanceTransaction?
    @Insert fun insertTransaction(row: FinanceTransaction)
    @Update fun updateTransaction(row: FinanceTransaction)
    @Query("DELETE FROM transactions WHERE id=:id") fun deleteTransaction(id: String)
    @Query("SELECT * FROM transactions WHERE bank=:bank AND reference=:reference AND id!=:exceptId LIMIT 1")
    fun referenceDuplicate(bank: String, reference: String, exceptId: String): FinanceTransaction?
    @Query("SELECT * FROM transactions WHERE id!=:exceptId AND amountMinor=:amount AND currency=:currency AND bank=:bank AND accountLabel=:account AND type=:type AND direction=:direction AND occurredAt BETWEEN :start AND :end LIMIT 1")
    fun probableDuplicate(exceptId: String, amount: Long, currency: String, bank: String, account: String,
        type: String, direction: String, start: Long, end: Long): FinanceTransaction?
    @Query("SELECT * FROM categories ORDER BY `key`") fun categories(): List<Category>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun seedCategories(rows: List<Category>)
    @Upsert fun saveCategory(row: Category)
    @Query("UPDATE transactions SET categoryKey='other', updatedAt=:now WHERE categoryKey=:key") fun reassignCategory(key: String, now: Long)
    @Query("DELETE FROM categories WHERE `key`=:key") fun deleteCategory(key: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun enqueue(row: PendingMessage): Long
    @Query("SELECT * FROM pending_messages WHERE id=:id") fun message(id: String): PendingMessage?
    @Query("SELECT * FROM pending_messages ORDER BY receivedAt") fun messages(): List<PendingMessage>
    @Update fun updateMessage(row: PendingMessage)
    @Query("UPDATE pending_messages SET status='PROCESSING', leaseUntil=:lease, attempts=attempts+1 WHERE id=:id AND queuedProviderId=:provider AND ((status='PENDING' AND nextAttemptAt<=:now) OR (status='PROCESSING' AND leaseUntil<=:now)) AND attempts<5")
    fun claim(id: String, provider: String, now: Long, lease: Long): Int
    @Query("SELECT * FROM processing_stats WHERE id=1") fun stats(): ProcessingStats?
    @Upsert fun saveStats(stats: ProcessingStats)
}

@Database(entities = [FinanceTransaction::class, PendingMessage::class, Category::class, ProcessingStats::class], version = 1, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun dao(): FinanceDao
    companion object {
        fun open(context: Context, name: String = "finance.db", test: Boolean = false): FinanceDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, FinanceDatabase::class.java, name)
            if (test) builder.allowMainThreadQueries()
            val db = builder.build()
            db.runInTransaction { db.dao().seedCategories(SeedCategories.all) }
            return db
        }
    }
}

object SeedCategories {
    val all = listOf(
        Category("groceries", "Groceries", "بقالة"), Category("dining", "Dining", "مطاعم"),
        Category("coffee", "Coffee", "قهوة"), Category("transport", "Transport", "مواصلات"),
        Category("fuel", "Fuel", "وقود"), Category("utilities", "Utilities", "خدمات"),
        Category("telecom", "Telecom", "اتصالات"), Category("shopping", "Shopping", "تسوق"),
        Category("health", "Health", "صحة"), Category("education", "Education", "تعليم"),
        Category("entertainment", "Entertainment", "ترفيه"), Category("travel", "Travel", "سفر"),
        Category("government_fees", "Government fees", "رسوم حكومية"), Category("cash", "Cash", "نقد"),
        Category("transfers", "Transfers", "تحويلات"), Category("income", "Income", "دخل"), Category("other", "Other", "أخرى"),
    )
}

enum class SaveResult { SAVED, DUPLICATE_REFERENCE, PROBABLE_DUPLICATE }

class Ledger(val db: FinanceDatabase) {
    val dao get() = db.dao()
    fun enqueue(row: PendingMessage): Boolean = dao.enqueue(row) != -1L
    fun saveCategory(row: Category) {
        require(row.key.matches(Regex("[a-z][a-z0-9_]{0,39}")) && row.labelEn.isNotBlank() && row.labelAr.isNotBlank()) { "CATEGORY" }
        dao.saveCategory(row)
    }
    fun deleteCategory(key: String) {
        require(key != "other") { "CATEGORY_REQUIRED" }
        db.runInTransaction { dao.reassignCategory(key, System.currentTimeMillis()); dao.deleteCategory(key) }
    }
    fun duplicate(row: FinanceTransaction): SaveResult? {
        if (row.reference != null && dao.referenceDuplicate(row.bank, row.reference, row.id) != null) return SaveResult.DUPLICATE_REFERENCE
        if (row.reference == null && dao.probableDuplicate(row.id, row.amountMinor, row.currency, row.bank,
                row.accountLabel, row.type, row.direction, row.occurredAt - 180_000, row.occurredAt + 180_000) != null) return SaveResult.PROBABLE_DUPLICATE
        return null
    }
    fun saveManual(row: FinanceTransaction, confirmProbable: Boolean = false): SaveResult {
        var result = SaveResult.SAVED
        db.runInTransaction {
            val existing = dao.transaction(row.id)
            val dup = duplicate(row)
            if (dup == SaveResult.DUPLICATE_REFERENCE || (dup == SaveResult.PROBABLE_DUPLICATE && !confirmProbable)) result = dup
            else if (existing == null) dao.insertTransaction(row) else dao.updateTransaction(row.copy(createdAt = existing.createdAt, updatedAt = System.currentTimeMillis()))
        }
        return result
    }
    fun ignored() = db.runInTransaction {
        val stats = dao.stats() ?: ProcessingStats()
        dao.saveStats(stats.copy(ignored = Math.addExact(stats.ignored, 1)))
    }
}
