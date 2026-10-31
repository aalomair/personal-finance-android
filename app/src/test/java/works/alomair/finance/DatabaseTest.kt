package works.alomair.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseTest {
    @Test fun persistentCrudCategoriesAndQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("persistence-test")
        var db = FinanceDatabase.open(context, "persistence-test", test = true)
        val store = Ledger(db)
        assertEquals(17, db.dao().categories().size)
        assertEquals("بقالة", db.dao().categories().first { it.key == "groceries" }.labelAr)
        val category = Category("books", "Books", "كتب")
        store.saveCategory(category)
        val row = FinanceTransaction(amountMinor = 1050, currency = "SAR", occurredAt = 1000,
            type = "expense", direction = "out", merchant = "Synthetic Shop", categoryKey = "books")
        assertEquals(SaveResult.SAVED, store.saveManual(row))
        assertEquals(row.id, db.dao().transactions().single().id)
        store.saveManual(row.copy(merchant = "Edited", categoryKey = "coffee"))
        assertEquals("coffee", db.dao().transaction(row.id)!!.categoryKey)
        store.saveManual(row.copy(categoryKey = "books"))
        store.deleteCategory("books")
        assertEquals("other", db.dao().transaction(row.id)!!.categoryKey)
        store.saveCategory(db.dao().categories().first { it.key == "coffee" }.copy(labelEn = "Cafe", hidden = true))
        assertTrue(db.dao().categories().first { it.key == "coffee" }.hidden)
        val pending = PendingMessage(sender = "SyntheticBank", body = "Purchase SAR 10.50", receivedAt = 1000,
            bodyHash = "synthetic-hash", queuedProviderId = "GO")
        assertTrue(store.enqueue(pending))
        db.close()
        db = FinanceDatabase.open(context, "persistence-test", test = true)
        assertEquals("Purchase SAR 10.50", db.dao().message(pending.id)!!.body)
        assertEquals("sms", FinanceTransaction(amountMinor = 1, currency = "JPY", occurredAt = 0,
            type = "income", direction = "in", source = "sms").source)
        assertEquals(row.id, db.dao().transactions().single().id)
        java.util.UUID.fromString(row.id)
        db.dao().deleteTransaction(row.id)
        assertTrue(db.dao().transactions().isEmpty())
        db.close()
    }

    @Test fun manualDuplicateWarningNeedsConfirmationAndReferenceIndexCannotBeBypassed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("duplicate-test")
        val db = FinanceDatabase.open(context, "duplicate-test", test = true)
        try {
            val ledger = Ledger(db)
            val first = FinanceTransaction(type = "expense", direction = "out", amountMinor = 1000,
                currency = "SAR", occurredAt = 1000, bank = "SyntheticBank", accountLabel = "4321")
            assertEquals(SaveResult.SAVED, ledger.saveManual(first))
            val second = first.copy(id = java.util.UUID.randomUUID().toString())
            assertEquals(SaveResult.PROBABLE_DUPLICATE, ledger.saveManual(second))
            assertEquals(1, db.dao().transactions().size)
            assertEquals(SaveResult.SAVED, ledger.saveManual(second, confirmProbable = true))
            val reference = first.copy(id = java.util.UUID.randomUUID().toString(), reference = "SYN0001")
            assertEquals(SaveResult.SAVED, ledger.saveManual(reference))
            assertEquals(SaveResult.DUPLICATE_REFERENCE, ledger.saveManual(reference.copy(id = java.util.UUID.randomUUID().toString()), confirmProbable = true))
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.dao().insertTransaction(reference.copy(id = java.util.UUID.randomUUID().toString()))
            }
        } finally { db.close() }
    }

    @Test fun queueClaimIsAtomicAndSourceMessageIndexIsUnique() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("claim-test")
        val db = FinanceDatabase.open(context, "claim-test", test = true)
        try {
            val message = PendingMessage(sender = "SyntheticBank", body = "Purchase SAR 1", receivedAt = 1000,
                bodyHash = "synthetic-claim", queuedProviderId = "GO")
            assertTrue(Ledger(db).enqueue(message))
            assertEquals(0, db.dao().claim(message.id, "ROUTER", 1000, 2000))
            assertEquals(1, db.dao().claim(message.id, "GO", 1000, 2000))
            assertEquals(0, db.dao().claim(message.id, "GO", 1000, 2000))
            val row = FinanceTransaction(type = "expense", direction = "out", amountMinor = 100,
                currency = "SAR", occurredAt = 1000, source = "sms", sourceMessageId = message.id)
            db.dao().insertTransaction(row)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.dao().insertTransaction(row.copy(id = java.util.UUID.randomUUID().toString()))
            }
        } finally { db.close() }
    }
}
