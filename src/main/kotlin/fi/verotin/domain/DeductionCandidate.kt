package fi.verotin.domain
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
/**
 * A POSSIBLE tax-deduction candidate produced by the LLM.
 *
 * IMPORTANT: This is NOT a final tax claim or legal advice.
 * It is a reviewable candidate that requires human verification
 * before any tax-filing action is taken.
 */
data class DeductionCandidate(
    val id: UUID,
    val invoiceExtractionId: UUID,
    /** Finnish deduction category, e.g. 'työvöline', 'tyohuonevähennys', 'koulutus' */
    val category: String,
    val deductibleAmount: BigDecimal?,
    /** 0.0 – 1.0; reflects LLM uncertainty, NOT a legal certainty */
    val confidence: Double,
    /** Evidence-based justification from the document and tax rules */
    val justification: String,
    /** What additional information would increase or decrease confidence */
    val missingInformation: String?,
    /** Human-readable next step, e.g. 'Tarkista etätyösopimus' */
    val suggestedNextAction: String?,
    /** Short snippets of evidence text from document/tax-rule chunks */
    val evidenceSnippets: List<String>,
    val taxYear: Int?,
    val status: CandidateStatus,
    val rawLlmResponse: String,
    val createdAt: Instant,
)
enum class CandidateStatus { PENDING, ACCEPTED, REJECTED }
