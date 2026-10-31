package works.alomair.finance

import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object SmsFilter {
    private val otp = Regex("otp|one[ -]?time|verification|verify|do not share|password|رمز|التحقق|كلمة المرور|لا تشارك|تفعيل", RegexOption.IGNORE_CASE)
    private val movement = Regex("purchase|debit|credit|transfer|withdraw|deposit|payment|salary|refund|شراء|مدى|حوالة|حواله|إيداع|ايداع|سحب|سداد|راتب|استرداد", RegexOption.IGNORE_CASE)
    fun isCandidate(body: String): Boolean = !otp.containsMatchIn(body) &&
        (movement.containsMatchIn(body) || CurrencySignals.amounts(body).isNotEmpty())
    fun hash(sender: String, body: String, receivedAt: Long): String {
        val normalized = Money.normalize(body).trim().replace(Regex("\\s+"), " ")
        val canonical = "$sender\u0000$normalized\u0000${receivedAt / 60_000}"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object CurrencySignals {
    private val codes = Currency.getAvailableCurrencies().map { it.currencyCode }.sorted().joinToString("|")
    private val token = "(?:ر\\.?\\s?س\\.?|ريال(?: سعودي)?|(?<![A-Za-z])(?:SAR|SR|$codes)(?![A-Za-z]))"
    private val number = "(?<![0-9.,])(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?![0-9.,])"
    private val before = Regex("($token)\\s*[:=]?\\s*($number)", RegexOption.IGNORE_CASE)
    private val after = Regex("($number)\\s*($token)", RegexOption.IGNORE_CASE)
    private fun code(value: String): String = if (value.startsWith("ر") || value.equals("SR", true)) "SAR" else value.uppercase(Locale.ROOT)
    fun amounts(body: String): List<Pair<String, String>> {
        val normalized = Money.normalize(body)
        return before.findAll(normalized).map { it.groupValues[2] to code(it.groupValues[1]) }.toList() +
            after.findAll(normalized).map { it.groupValues[1] to code(it.groupValues[2]) }.toList()
    }
}

class ValidationResult(val draftJson: String, val transaction: FinanceTransaction?, val errorCode: String?) {
    override fun toString() = "ValidationResult(errorCode=$errorCode)"
}

object ExtractionValidator {
    private val fields = setOf("is_transaction", "type", "direction", "amount", "currency", "occurred_at",
        "merchant", "bank", "account", "category", "reference", "confidence")

    // Balanced extraction respects braces inside strings; never repairs truncated JSON.
    private fun firstObject(text: String): JSONObject {
        if (text.length > 16_384) throw ApiFailure("MALFORMED_JSON", true, true)
        val start = text.indexOf('{')
        if (start < 0) throw ApiFailure("MALFORMED_JSON", true, true)
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) {
                    try { return JSONObject(text.substring(start, i + 1)) }
                    catch (_: Exception) { throw ApiFailure("MALFORMED_JSON", true, true) }
                } }
            }
        }
        throw ApiFailure("MALFORMED_JSON", true, true)
    }

    fun validate(text: String, sms: PendingMessage, categories: List<String>): ValidationResult {
        val parsed = firstObject(text)
        val draft = JSONObject()
        for (field in fields) if (parsed.has(field)) draft.put(field, parsed.get(field))
        fun fail(code: String) = ValidationResult(draft.toString(), null, code)
        if (!fields.all { parsed.has(it) }) return fail("INCOMPLETE")
        if (parsed.opt("is_transaction") !is Boolean) return fail("INCOMPLETE")
        if (!parsed.getBoolean("is_transaction")) return fail("NOT_TRANSACTION")
        fun string(field: String): String? = parsed.opt(field).let { if (it is String && it.length <= 300) it else null }
        val type = string("type") ?: return fail("INCOMPLETE")
        val direction = string("direction") ?: return fail("INCOMPLETE")
        if (type !in setOf("income", "expense", "transfer") || direction !in setOf("in", "out") ||
            (type == "income" && direction != "in") || (type == "expense" && direction != "out")) return fail("ENUM")
        val category = string("category") ?: return fail("INCOMPLETE")
        if (category !in categories) return fail("CATEGORY")
        val confidence = try { BigDecimal(parsed.get("confidence").toString()) } catch (_: Exception) { return fail("CONFIDENCE") }
        if (confidence < BigDecimal.ZERO || confidence > BigDecimal.ONE) return fail("CONFIDENCE")
        if (confidence < BigDecimal("0.8")) return fail("LOW_CONFIDENCE")
        val currency = string("currency") ?: return fail("INCOMPLETE")
        try { Money.exponent(currency) } catch (_: IllegalArgumentException) { return fail("CURRENCY") }
        val amount = string("amount") ?: return fail("INCOMPLETE")
        val minor = try { Money.minor(amount, currency) } catch (_: IllegalArgumentException) { return fail("AMOUNT") }
        val tokens = CurrencySignals.amounts(sms.body)
        if (tokens.none { it.second == currency }) return fail("CURRENCY_MISMATCH")
        val values = tokens.mapNotNull { (value, code) ->
            try { code to Money.minor(value, code) } catch (_: IllegalArgumentException) { null }
        }.distinct()
        if ((currency to minor) !in values) return fail("AMOUNT_NOT_IN_SMS")
        if (values.size != 1) return fail("AMBIGUOUS_AMOUNTS")
        val body = Money.normalize(sms.body)
        fun optional(field: String): String? = if (parsed.isNull(field)) null else string(field)?.takeIf { it.isNotBlank() }
        val account = optional("account")
        if (!parsed.isNull("account") && account == null) return fail("ACCOUNT_NOT_IN_SMS")
        val normalizedAccount = account?.let { Money.normalize(it) }
        if (normalizedAccount != null && (!normalizedAccount.matches(Regex("[0-9]{2,8}")) ||
            !Regex("[0-9]+").findAll(body).any { it.value.endsWith(normalizedAccount) })) return fail("ACCOUNT_NOT_IN_SMS")
        val reference = optional("reference")?.let { Money.normalize(it) }
        if (!parsed.isNull("reference") && reference == null) return fail("REFERENCE_NOT_IN_SMS")
        if (reference != null && !Regex("(?<![\\p{L}\\p{N}])${Regex.escape(reference)}(?![\\p{L}\\p{N}])").containsMatchIn(body)) return fail("REFERENCE_NOT_IN_SMS")
        val merchant = string("merchant") ?: return fail("INCOMPLETE")
        if (merchant.isBlank() || !body.contains(Money.normalize(merchant), ignoreCase = true)) return fail("MERCHANT_NOT_IN_SMS")
        val bank = string("bank") ?: return fail("INCOMPLETE")
        if (bank != sms.sender) return fail("BANK_MISMATCH")
        var occurredAt = sms.receivedAt
        val date = optional("occurred_at")
        if (!parsed.isNull("occurred_at") && date == null) return fail("DATE")
        if (date != null) {
            val parsedDate = try {
                if (date.length == 10) LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Riyadh"))
                else OffsetDateTime.parse(date).toZonedDateTime()
            } catch (_: Exception) { return fail("DATE") }
            occurredAt = parsedDate.toInstant().toEpochMilli()
            if (occurredAt < sms.receivedAt - 7 * 86_400_000L || occurredAt > sms.receivedAt + 86_400_000L) return fail("DATE_RANGE")
            val localDate = parsedDate.toLocalDate()
            val dateTokens = listOf(localDate.toString(), localDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                localDate.format(DateTimeFormatter.ofPattern("d/M/yyyy")))
            if (dateTokens.none { body.contains(it) }) return fail("DATE_NOT_IN_SMS")
            if (date.length > 10 && !body.contains(parsedDate.format(DateTimeFormatter.ofPattern("HH:mm")))) return fail("TIME_NOT_IN_SMS")
        } else if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}").containsMatchIn(body)) return fail("DATE_OMITTED")
        return ValidationResult(draft.toString(), FinanceTransaction(type = type, direction = direction, amountMinor = minor,
            currency = currency, occurredAt = occurredAt, merchant = merchant, bank = sms.sender,
            accountLabel = normalizedAccount ?: "", categoryKey = category, reference = reference,
            source = "sms", sourceMessageId = sms.id), null)
    }
}
