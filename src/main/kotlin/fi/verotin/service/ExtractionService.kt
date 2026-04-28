package fi.verotin.service
import fi.verotin.domain.InvoiceExtraction
import fi.verotin.domain.LineItem
import fi.verotin.domain.SourceDocument
import fi.verotin.ollama.OllamaClient
import fi.verotin.ollama.OllamaException
import fi.verotin.ollama.OllamaMessage
import fi.verotin.config.OllamaProperties
import fi.verotin.repository.InvoiceExtractionRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
/**
 * Extracts structured invoice fields from a document using the LLM.
 * Uses JSON-mode output from Ollama to ensure parseable structured data.
 *
 * Error handling: extraction failures are logged and a minimal record is returned.
 * Every field is nullable; the LLM may not find all fields in every document.
 */
@Service
class ExtractionService(
    private val ollamaClient: OllamaClient,
    private val props: OllamaProperties,
    private val extractionRepo: InvoiceExtractionRepository,
) {
    private val log = LoggerFactory.getLogger(ExtractionService::class.java)
    private val objectMapper = jacksonObjectMapper()
    fun extract(doc: SourceDocument): InvoiceExtraction {
        val systemPrompt = """
            You are an invoice and receipt extraction system. 
            Analyze the provided document text and extract key invoice/receipt fields.
            Return ONLY a valid JSON object with the following schema:
            {
              "vendorName": "string or null",
              "invoiceNumber": "string or null",
              "invoiceDate": "YYYY-MM-DD or null",
              "paymentDate": "YYYY-MM-DD or null",
              "totalAmount": number (decimal) or null,
              "currency": "string (ISO 4217 code) or null",
              "vatAmount": number (decimal) or null,
              "lineItemsTable": {
                "columns": [
                  { "text": <column text>, "type": <column type> }
                ],
                "rows": [["value1", "value2", "value3", ...], ["value1", "value2", "value3", ...]]
              }
            }
            Rules:
            - Be conservative: if unsure, use null.
            - Dates must be in YYYY-MM-DD format.
            - Amounts are numbers (not strings).
            - Finnish locale: look for "alv" or "vero" for VAT.
            - lineItemsTable.columns should contain extracted header names in order with:
              - text: exact header text seen in document
              - type: one of
                - CODE
                - DESCRIPTION (nimike, otsikko)
                - QUANTITY (määrä, kpl)
                - UNIT (yksikkö)
                - UNIT_PRICE (à, à-hinta, a-hinta)
                - NET_TOTAL (veroton hinta)
                - GROSS_TOTAL (verollinen hinta)
                - VAT_RATE (%, alv, alv-%)
                - UNKNOWN (if determining meaning is unclear)
            - Product title/name column must be typed as DESCRIPTION whenever present (e.g. Nimike, Tuotenimi, Kuvaus, Selite).
            - Each line has title/description. If title is split across columns, keep the most descriptive title text in DESCRIPTION cell for each row.
            - Do not put product title in CODE column.
            - lineItemsTable.rows should contain raw row values in the same order as columns.
            - If a product title wraps across multiple physical lines, keep it in a single row description.
            - Do not create a separate row for a continuation fragment of the previous product title.
            - If no table exists, return lineItemsTable with empty columns/rows.
            - important: each rows array size should always match column count
            - important: each column type is used only once
        """.trimIndent()
        val userPrompt = """
            Extract invoice fields from this document:
            ${doc.rawContent.take(12000)}
        """.trimIndent()
        val rawResponse = try {
            ollamaClient.chat(
                model = props.chatModel,
                messages = listOf(
                    OllamaMessage(role = "system", content = systemPrompt),
                    OllamaMessage(role = "user", content = userPrompt),
                ),
                jsonFormat = true,
            )
        } catch (ex: OllamaException) {
            log.error("Extraction failed for ${doc.filename}: ${ex.message}")
            return InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = null,
                invoiceNumber = null,
                invoiceDate = null,
                paymentDate = null,
                totalAmount = null,
                currency = null,
                vatAmount = null,
                laborAmount = null,
                materialAmount = null,
                lineItems = emptyList(),
                rawLlmResponse = "ERROR: ${ex.message}",
                extractedAt = Instant.now(),
            ).also {
                extractionRepo.insert(it)
            }
        }
        val extraction = try {
            val parsed = objectMapper.readValue(rawResponse, ExtractionDto::class.java)
            val tableLineItems = mapLineItemsFromTable(parsed.lineItemsTable)

//            val normalizedLineItems = normalizeLineTotalsWithVatContext(
//                lineItems = tableLineItems,
//                invoiceTotal = parsed.totalAmount?.let { BigDecimal(it.toString()) },
//                invoiceVat = parsed.vatAmount?.let { BigDecimal(it.toString()) },
//            )

            InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = parsed.vendorName?.trim().takeIf { !it.isNullOrBlank() },
                invoiceNumber = parsed.invoiceNumber?.trim().takeIf { !it.isNullOrBlank() },
                invoiceDate = parsed.invoiceDate?.let { parseDate(it) },
                paymentDate = parsed.paymentDate?.let { parseDate(it) },
                totalAmount = parsed.totalAmount?.let { BigDecimal(it.toString()) },
                currency = parsed.currency?.trim().takeIf { !it.isNullOrBlank() },
                vatAmount = parsed.vatAmount?.let { BigDecimal(it.toString()) },
                laborAmount = null,
                materialAmount = null,
                lineItems = tableLineItems,
                rawLlmResponse = rawResponse,
                extractedAt = Instant.now(),
            )
        } catch (ex: Exception) {
            log.warn("Failed to parse extraction JSON, storing partial result", ex)
            InvoiceExtraction(
                id = java.util.UUID.randomUUID(),
                sourceDocumentId = doc.id,
                vendorName = null,
                invoiceNumber = null,
                invoiceDate = null,
                paymentDate = null,
                totalAmount = null,
                currency = null,
                vatAmount = null,
                laborAmount = null,
                materialAmount = null,
                lineItems = emptyList(),
                rawLlmResponse = rawResponse,
                extractedAt = Instant.now(),
            )
        }
        extractionRepo.insert(extraction)
        log.debug("Extracted invoice from ${doc.filename}: vendor=${extraction.vendorName}, amount=${extraction.totalAmount}")
        return extraction
    }
    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr.trim())
        } catch (ex: Exception) {
            log.debug("Failed to parse date: $dateStr")
            null
        }
    }

    private fun normalizeLineTotalsWithVatContext(
        lineItems: List<LineItem>,
        invoiceTotal: BigDecimal?,
        invoiceVat: BigDecimal?,
    ): List<LineItem> {
        val vatMultiplier = inferVatMultiplier(invoiceTotal, invoiceVat) ?: return lineItems

        return lineItems.map { item ->
            // Only normalize NET_TOTAL to GROSS_TOTAL when we have VAT context
            if (item.totalType != LineColumnType.NET_TOTAL.name) return@map item
            if (item.total == null) return@map item

            val vatInclusive = item.total.multiply(vatMultiplier).setScale(2, java.math.RoundingMode.HALF_UP)
            item.copy(total = vatInclusive, totalType = LineColumnType.GROSS_TOTAL.name)
        }
    }

    private fun inferVatMultiplier(invoiceTotal: BigDecimal?, invoiceVat: BigDecimal?): BigDecimal? {
        if (invoiceTotal == null || invoiceVat == null) return null
        val net = invoiceTotal.subtract(invoiceVat)
        if (net <= BigDecimal.ZERO) return null
        val multiplier = invoiceTotal.divide(net, 6, java.math.RoundingMode.HALF_UP)
        return if (multiplier > BigDecimal("1.00") && multiplier < BigDecimal("1.30")) multiplier else null
    }

    private fun mapLineItemsFromTable(table: LineItemsTableDto?): List<LineItem> {
        val columnsRaw = table?.columns ?: return emptyList()
        val rowsRaw = table.rows ?: return emptyList()
        if (columnsRaw.isEmpty() || rowsRaw.isEmpty()) return emptyList()

        val descriptors = columnsRaw.map { normalizeColumnDescriptor(it) }
        val indexByType = buildColumnIndexByType(descriptors)
        val descIdx = indexByType[LineColumnType.DESCRIPTION] ?: resolveDescriptionColumn(descriptors)
        val qtyIdx = indexByType[LineColumnType.QUANTITY] ?: resolveQuantityColumn(descriptors)
        val unitIdx = indexByType[LineColumnType.UNIT_PRICE] ?: resolveUnitPriceColumn(descriptors)

        // Track which total type we're using
        val (totalIdx, totalType) = when {
            LineColumnType.GROSS_TOTAL in indexByType ->
                Pair(indexByType[LineColumnType.GROSS_TOTAL], LineColumnType.GROSS_TOTAL.name)
            LineColumnType.NET_TOTAL in indexByType ->
                Pair(indexByType[LineColumnType.NET_TOTAL], LineColumnType.NET_TOTAL.name)
            else -> Pair(resolveTotalColumn(descriptors), null)
        }

        return rowsRaw.mapNotNull { row ->
            val cells = row ?: return@mapNotNull null
            val description = resolveDescription(cells, descriptors, descIdx)
            if (description.isBlank()) return@mapNotNull null

            val unitPrice = resolveUnitPrice(cells, descriptors, unitIdx)
            val total = resolveTotal(cells, descriptors, totalIdx)
            val quantity = resolveQuantity(cells, descriptors, qtyIdx, unitPrice)

            LineItem(
                description = description,
                quantity = quantity,
                unitPrice = unitPrice,
                total = total,
                totalType = totalType,
            )
        }
    }

    private fun resolveQuantity(
        row: List<Any?>,
        columns: List<ColumnDescriptor>,
        qtyIdx: Int?,
        unitPrice: BigDecimal?,
    ): Double? {
        val direct = readCell(row, qtyIdx)?.let { parseDecimal(it) }?.toDouble()
        if (direct != null) return direct

        val quantityByHeader = columns.indices
            .asSequence()
            .filter { idx ->
                columns[idx].type == LineColumnType.QUANTITY ||
                    columns[idx].text.contains("maara") ||
                    columns[idx].text.contains("qty") ||
                    columns[idx].text.contains("kpl")
            }
            .mapNotNull { idx -> readCell(row, idx)?.let { parseDecimal(it) }?.toDouble() }
            .firstOrNull()
        if (quantityByHeader != null) return quantityByHeader

        if (unitPrice == null || unitPrice <= BigDecimal.ZERO) return null

        val candidateTotals = columns.indices
            .asSequence()
            .filter { idx ->
                columns[idx].type == LineColumnType.GROSS_TOTAL ||
                    columns[idx].type == LineColumnType.NET_TOTAL ||
                    columns[idx].text.contains("verollinen") ||
                    columns[idx].text.contains("veroton") ||
                    columns[idx].text.contains("summa") ||
                    columns[idx].text.contains("total")
            }
            .mapNotNull { idx -> readCell(row, idx)?.let { parseDecimal(it) } }
            .toList()

        candidateTotals.forEach { total ->
            if (total <= BigDecimal.ZERO) return@forEach
            val raw = total.divide(unitPrice, 4, java.math.RoundingMode.HALF_UP)
            val rounded = raw.setScale(0, java.math.RoundingMode.HALF_UP)
            if (rounded <= BigDecimal.ZERO || rounded > BigDecimal("10000")) return@forEach
            val delta = raw.subtract(rounded).abs()
            if (delta <= BigDecimal("0.05")) {
                return rounded.toDouble()
            }
        }
        return null
    }

    private fun resolveUnitPrice(row: List<Any?>, columns: List<ColumnDescriptor>, unitIdx: Int?): BigDecimal? {
        val direct = readCell(row, unitIdx)?.let { parseDecimal(it) }
        if (direct != null) return direct

        val fallbackIdx = columns.indexOfFirst {
            it.type == LineColumnType.UNIT_PRICE ||
                it.text.contains("a-hinta") ||
                it.text.contains("yksikkohinta")
        }
        if (fallbackIdx < 0) return null
        return readCell(row, fallbackIdx)?.let { parseDecimal(it) }
    }

    private fun resolveTotal(row: List<Any?>, columns: List<ColumnDescriptor>, totalIdx: Int?): BigDecimal? {
        val direct = readCell(row, totalIdx)?.let { parseDecimal(it) }
        if (direct != null) return direct

        val grossIdx = columns.indexOfFirst {
            it.type == LineColumnType.GROSS_TOTAL ||
                it.text.contains("verollinen") ||
                it.text.contains("incl vat")
        }
        if (grossIdx >= 0) {
            val gross = readCell(row, grossIdx)?.let { parseDecimal(it) }
            if (gross != null) return gross
        }

        val netIdx = columns.indexOfFirst {
            it.type == LineColumnType.NET_TOTAL ||
                it.text.contains("veroton") ||
                it.text.contains("net")
        }
        if (netIdx >= 0) {
            val net = readCell(row, netIdx)?.let { parseDecimal(it) }
            if (net != null) return net
        }
        return null
    }

    private fun resolveDescription(
        row: List<Any?>,
        columns: List<ColumnDescriptor>,
        descIdx: Int?,
    ): String {
        // Try direct column first
        val direct = readCell(row, descIdx)?.trim().orEmpty()
        if (direct.isNotBlank()) {
            return direct
        }

        // Fallback: find longest text in row that's not a numeric/price/quantity column
        val fallback = row.indices
            .mapNotNull { idx ->
                val value = readCell(row, idx)?.trim().orEmpty()
                if (value.isBlank() || !containsLetters(value)) return@mapNotNull null
                if (columns.getOrNull(idx)?.type in listOf(
                    LineColumnType.QUANTITY,
                    LineColumnType.UNIT_PRICE,
                    LineColumnType.NET_TOTAL,
                    LineColumnType.GROSS_TOTAL,
                    LineColumnType.VAT_RATE
                )) return@mapNotNull null
                value
            }
            .maxByOrNull { it.length }

        return fallback.orEmpty()
    }

    private fun buildColumnIndexByType(columns: List<ColumnDescriptor>): Map<LineColumnType, Int> {
        val preferredOrder = listOf(
            LineColumnType.DESCRIPTION,
            LineColumnType.QUANTITY,
            LineColumnType.UNIT_PRICE,
            LineColumnType.GROSS_TOTAL,
            LineColumnType.NET_TOTAL,
            LineColumnType.VAT_RATE,
            LineColumnType.CODE,
        )

        val indexByType = mutableMapOf<LineColumnType, Int>()
        preferredOrder.forEach { type ->
            val idx = columns.indexOfFirst { it.type == type }
            if (idx >= 0) {
                indexByType[type] = idx
            }
        }
        return indexByType
    }

    private fun resolveDescriptionColumn(columns: List<ColumnDescriptor>): Int? {
        val typed = columns.indexOfFirst { it.type == LineColumnType.DESCRIPTION }
        if (typed >= 0) return typed
        val candidates = listOf("nimike", "kuvaus", "selite", "tuote", "description")
        return columns.indexOfFirst { col -> candidates.any { col.text.contains(it) } }
            .takeIf { it >= 0 }
    }

    private fun resolveQuantityColumn(columns: List<ColumnDescriptor>): Int? {
        val typed = columns.indexOfFirst { it.type == LineColumnType.QUANTITY }
        if (typed >= 0) return typed
        val candidates = listOf("maara", "kpl", "qty", "quantity")
        return columns.indexOfFirst { col -> candidates.any { col.text.contains(it) } }
            .takeIf { it >= 0 }
    }

    private fun resolveUnitPriceColumn(columns: List<ColumnDescriptor>): Int? {
        val typed = columns.indexOfFirst { it.type == LineColumnType.UNIT_PRICE }
        if (typed >= 0) return typed
        val candidates = listOf("a-hinta", "ahinta", "yksikkohinta", "unitprice", "unit price")
        return columns.indexOfFirst { col -> candidates.any { col.text.contains(it) } }
            .takeIf { it >= 0 }
    }

    private fun resolveTotalColumn(columns: List<ColumnDescriptor>): Int? {
        val grossTyped = columns.indexOfFirst { it.type == LineColumnType.GROSS_TOTAL }
        if (grossTyped >= 0) return grossTyped

        val netTyped = columns.indexOfFirst { it.type == LineColumnType.NET_TOTAL }
        if (netTyped >= 0) return netTyped

        val grossCandidates = listOf("verollinen", "sis alv", "incl vat", "gross")
        val netCandidates = listOf("veroton", "ex vat", "net")
        val genericCandidates = listOf("summa", "total", "hinta")

        val grossIdx = columns.indexOfFirst { col -> grossCandidates.any { col.text.contains(it) } }
        if (grossIdx >= 0) return grossIdx

        val netIdx = columns.indexOfFirst { col -> netCandidates.any { col.text.contains(it) } }
        if (netIdx >= 0) return netIdx

        return columns.indexOfFirst { col -> genericCandidates.any { col.text.contains(it) } }
            .takeIf { it >= 0 }
    }

    private fun normalizeColumnDescriptor(raw: LineItemsTableColumnDto): ColumnDescriptor {
        val normalizedType = runCatching { LineColumnType.valueOf(raw.type?.trim().orEmpty().uppercase()) }
            .getOrDefault(LineColumnType.UNKNOWN)
        return ColumnDescriptor(
            text = normalizeHeader(raw.text ?: ""),
            type = normalizedType,
        )
    }

    private fun readCell(row: List<Any?>, index: Int?): String? {
        if (index == null || index !in row.indices) return null
        val value = row[index] ?: return null
        return value.toString()
    }

    private fun parseDecimal(raw: String): BigDecimal? {
        val compact = raw.trim()
        if (compact.isEmpty()) return null
        val normalized = compact
            .replace(" ", "")
            .replace("EUR", "", ignoreCase = true)
            .replace("€", "")
            .replace(',', '.')
        return normalized.toBigDecimalOrNull()
    }

    private fun containsLetters(value: String): Boolean = value.any { it.isLetter() }

    private fun normalizeHeader(header: String): String {
        return header.lowercase()
            .replace("ä", "a")
            .replace("ö", "o")
            .replace("å", "a")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractionDto(
    val vendorName: String? = null,
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val paymentDate: String? = null,
    val totalAmount: Number? = null,
    val currency: String? = null,
    val vatAmount: Number? = null,
    val lineItemsTable: LineItemsTableDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LineItemsTableDto(
    val columns: List<LineItemsTableColumnDto>? = null,
    val rows: List<List<Any?>?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LineItemsTableColumnDto(
    val text: String? = null,
    val type: String? = null,
)

private data class ColumnDescriptor(
    val text: String,
    val type: LineColumnType,
)

private enum class LineColumnType {
    CODE,
    DESCRIPTION,
    QUANTITY,
    UNIT_PRICE,
    NET_TOTAL,
    GROSS_TOTAL,
    VAT_RATE,
    UNKNOWN,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LineItemDto(
    val description: String? = null,
    val quantity: Number? = null,
    val unitPrice: Number? = null,
    val total: Number? = null,
)
