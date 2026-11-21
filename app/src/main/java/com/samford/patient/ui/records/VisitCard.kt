package com.samford.patient.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.samford.patient.R
import com.samford.patient.core.db.DocumentEntity
import com.samford.patient.core.db.LabResultEntity
import com.samford.patient.core.db.PageEntity
import com.samford.patient.core.db.RecipientEntity
import com.samford.patient.data.VisitRecord
import com.samford.patient.ui.common.ElevatedSection
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.StatusPill
import com.samford.patient.ui.common.formatDate
import com.samford.patient.ui.common.formatDateTime
import com.samford.patient.ui.home.documentKindLabel
import com.samford.patient.ui.theme.Amber
import com.samford.patient.ui.theme.OkGreen
import com.samford.patient.ui.theme.SampleRed
import java.io.File

@Composable
fun VisitCard(
    record: VisitRecord,
    expanded: Boolean,
    onOpenDocument: (String) -> Unit,
    onOpenLabResult: (String) -> Unit,
    onOpenVisit: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val firstPagesFlow = remember(record.visit.id) { container.records.firstPages(record.visit.id) }
    val firstPages by firstPagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val thumbs = firstPages.associateBy { it.documentId }

    ElevatedSection(modifier.testTag("visit_card")) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (onOpenVisit != null) it.clickable { onOpenVisit(record.visit.id) } else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDate(record.visit.dateMillis),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                val who = listOfNotNull(record.visit.doctorName, record.visit.department).joinToString(" · ")
                if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            record.visit.status?.takeIf { it != "COMPLETED" }?.let { status ->
                val (label, color) = visitStatus(status)
                StatusPill(label, color)
            }
        }

        if (record.documents.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                record.documents.forEach { doc ->
                    DocumentTile(doc, thumbs[doc.id], record.recipients[doc.id].orEmpty(), expanded) { onOpenDocument(doc.id) }
                }
            }
        }

        if (record.labResults.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.records_lab_results), style = MaterialTheme.typography.titleSmall)
            record.labResults.forEach { LabResultRow(it) { onOpenLabResult(it.id) } }
        }
    }
}

@Composable
private fun DocumentTile(
    doc: DocumentEntity,
    firstPage: PageEntity?,
    recipients: List<RecipientEntity>,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val container = LocalAppContainer.current
    val thumb by produceState<File?>(null, firstPage?.sha256) {
        value = firstPage?.let { container.records.cachedFile(it.sha256) }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("document_card"),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val file = thumb
                    if (file != null) {
                        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 48.dp, height = 64.dp))
                    } else {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(width = 48.dp, height = 64.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(documentKindLabel(doc.kind), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.records_handwritten_by, doc.authorName ?: stringResource(R.string.your_doctor)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (doc.currentVersion > 1) StatusPill(stringResource(R.string.badge_amended), Amber)
                        if (doc.viewedVersion < doc.currentVersion) StatusPill(stringResource(R.string.badge_new), MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            val others = recipients.filter { it.kind != "PATIENT" }
            if (others.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                others.forEach { RecipientRow(it, expanded) }
            }
        }
    }
}

/** "Sent to Samford OPD Pharmacy: Dispensed" — the same handwriting reached each recipient. */
@Composable
fun RecipientRow(recipient: RecipientEntity, showNote: Boolean) {
    val (label, color) = deliveryState(recipient.state)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, top = 4.dp)
            .testTag("recipient_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.records_sent_to, recipient.name ?: recipientKindLabel(recipient.kind)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (showNote) {
                recipient.updatedAt?.let {
                    Text(formatDateTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recipient.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        StatusPill(label, color)
    }
}

@Composable
private fun LabResultRow(result: LabResultEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("lab_result_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.testName)
            Text(
                listOfNotNull(result.value, result.unit).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val (label, color) = resultFlag(result.flag)
        StatusPill(label, color)
    }
}

@Composable
fun recipientKindLabel(kind: String): String = when (kind) {
    "PHARMACY" -> stringResource(R.string.recipient_pharmacy)
    "LAB" -> stringResource(R.string.recipient_lab)
    "PATIENT" -> stringResource(R.string.recipient_patient)
    else -> kind
}

/** Unknown states are shown verbatim, as the contract requires. */
@Composable
fun deliveryState(state: String): Pair<String, Color> = when (state) {
    "AVAILABLE" -> stringResource(R.string.state_available) to MaterialTheme.colorScheme.secondary
    "VIEWED" -> stringResource(R.string.state_viewed) to MaterialTheme.colorScheme.primary
    "DISPENSED" -> stringResource(R.string.state_dispensed) to OkGreen
    "PARTIALLY_DISPENSED" -> stringResource(R.string.state_partially_dispensed) to Amber
    "NOT_DISPENSED" -> stringResource(R.string.state_not_dispensed) to SampleRed
    "SAMPLE_COLLECTED" -> stringResource(R.string.state_sample_collected) to Amber
    "RESULT_READY" -> stringResource(R.string.state_result_ready) to OkGreen
    "SUPERSEDED" -> stringResource(R.string.state_superseded) to MaterialTheme.colorScheme.outline
    else -> state to MaterialTheme.colorScheme.secondary
}

@Composable
fun resultFlag(flag: String?): Pair<String, Color> = when (flag) {
    "NORMAL" -> stringResource(R.string.flag_normal) to OkGreen
    "ABNORMAL" -> stringResource(R.string.flag_abnormal) to Amber
    "CRITICAL" -> stringResource(R.string.flag_critical) to SampleRed
    null -> stringResource(R.string.flag_reported) to MaterialTheme.colorScheme.secondary
    else -> flag to MaterialTheme.colorScheme.secondary
}

@Composable
fun visitStatus(status: String): Pair<String, Color> = when (status) {
    "BOOKED" -> stringResource(R.string.visit_booked) to MaterialTheme.colorScheme.secondary
    "WAITING" -> stringResource(R.string.visit_waiting) to Amber
    "CALLED" -> stringResource(R.string.visit_called) to OkGreen
    "IN_CONSULTATION" -> stringResource(R.string.visit_in_consultation) to OkGreen
    "ON_HOLD" -> stringResource(R.string.visit_on_hold) to Amber
    "MISSED" -> stringResource(R.string.visit_missed) to SampleRed
    "CANCELLED" -> stringResource(R.string.visit_cancelled) to SampleRed
    else -> status to MaterialTheme.colorScheme.secondary
}
