package com.samford.patient.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samford.patient.AppContainer
import com.samford.patient.R
import com.samford.patient.core.db.FeedItemEntity
import com.samford.patient.core.discovery.ServerStatus
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.sync.FeedKinds
import com.samford.patient.data.DocumentKinds
import com.samford.patient.ui.common.EmptyState
import com.samford.patient.ui.common.InfoBanner
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.common.ProfileSwitcher
import com.samford.patient.ui.common.SampleBanner
import com.samford.patient.ui.common.WarningBanner
import com.samford.patient.ui.common.formatDateTime
import com.samford.patient.ui.theme.Amber
import com.samford.patient.ui.theme.OkGreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val records = container.records
    val profiles = records.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active = records.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val feed = records.activeProfile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else records.feed(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastUpdated = records.lastUpdated.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sync = container.syncCoordinator.status
    val server = container.serverDirectory.state
    val sessionExpired = container.session.sessionExpired

    fun refresh() {
        viewModelScope.launch {
            if (container.serverDirectory.state.value.status != ServerStatus.ONLINE) container.serverDirectory.refresh()
            container.syncCoordinator.run(force = true, reason = "manual")
        }
    }

    fun select(profileId: String) {
        viewModelScope.launch { records.setActiveProfile(profileId) }
    }

    fun markRead(profileId: String) {
        viewModelScope.launch { records.markFeedRead(profileId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (String) -> Unit,
    onOpenLabResult: (String) -> Unit,
    onOpenVisit: (String) -> Unit,
    onOpenRecords: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val lastUpdated by vm.lastUpdated.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val expired by vm.sessionExpired.collectAsStateWithLifecycle()
    val sample = LocalSampleMode.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(active?.id, feed?.size) {
        val id = active?.id ?: return@LaunchedEffect
        delay(2_000)
        vm.markRead(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { ProfileSwitcher(profiles, active, vm::select) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = sync.running,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_feed"),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (sample) item { SampleBanner() }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusLine(sample, lastUpdated, server.status, sync.lastErrorCode)
                        if (!sample && server.status == ServerStatus.UPDATE_REQUIRED) {
                            WarningBanner(text = stringResource(R.string.server_update_required_body))
                        }
                        if (expired) {
                            WarningBanner(
                                text = stringResource(R.string.home_session_expired),
                                actionLabel = stringResource(R.string.home_sign_in_again),
                                onAction = { scope.launch { container.signOut() } },
                            )
                        }
                        if (!sample) NotificationPrompt()
                    }
                }
                val list = feed
                when {
                    list == null -> Unit
                    list.isEmpty() -> item {
                        EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
                    }
                    else -> items(list, key = { it.id }) { item ->
                        FeedRow(item) {
                            when (item.refType) {
                                "document" -> onOpenDocument(item.refId)
                                "labResult" -> onOpenLabResult(item.refId)
                                "visit" -> onOpenVisit(item.refId)
                                else -> onOpenRecords()
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(sample: Boolean, lastUpdated: Long?, status: ServerStatus, error: String?) {
    val text = when {
        sample -> stringResource(R.string.home_sample_status)
        lastUpdated == null -> stringResource(R.string.home_never_updated)
        status == ServerStatus.UNREACHABLE || status == ServerStatus.OFFLINE || error == ApiError.CODE_NETWORK ->
            stringResource(R.string.home_offline_status, formatDateTime(lastUpdated))
        else -> stringResource(R.string.home_updated_at, formatDateTime(lastUpdated))
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("last_updated"),
    )
}

@Composable
private fun NotificationPrompt() {
    if (Build.VERSION.SDK_INT < 33) return
    val container = LocalAppContainer.current
    var granted by remember { mutableStateOf(container.notifier.canPost()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    if (granted || dismissed) return
    InfoBanner(
        text = stringResource(R.string.home_notifications_prompt),
        actionLabel = stringResource(R.string.action_allow),
        onAction = {
            dismissed = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
}

@Composable
fun feedTitle(item: FeedItemEntity): String {
    val doctor = item.actor ?: stringResource(R.string.your_doctor)
    val pharmacy = item.actor ?: stringResource(R.string.the_pharmacy)
    return when (item.kind) {
        FeedKinds.DOCUMENT_NEW -> when (item.subject) {
            DocumentKinds.PRESCRIPTION -> stringResource(R.string.feed_new_prescription, doctor)
            DocumentKinds.VISIT_SUMMARY -> stringResource(R.string.feed_new_visit_summary, doctor)
            DocumentKinds.INVESTIGATIONS -> stringResource(R.string.feed_new_investigations, doctor)
            DocumentKinds.ADVICE_REFERRAL -> stringResource(R.string.feed_new_advice, doctor)
            else -> stringResource(R.string.feed_new_document, doctor)
        }
        FeedKinds.DOCUMENT_AMENDED -> stringResource(R.string.feed_amended, documentKindLabel(item.subject), doctor)
        FeedKinds.LAB_RESULT_READY -> item.subject?.let { stringResource(R.string.feed_lab_result_ready, it) }
            ?: stringResource(R.string.feed_lab_results_ready)
        FeedKinds.LAB_SAMPLE_COLLECTED -> stringResource(R.string.feed_sample_collected, item.actor ?: stringResource(R.string.recipient_lab))
        FeedKinds.MEDICINES_DISPENSED -> stringResource(R.string.feed_dispensed, pharmacy)
        FeedKinds.MEDICINES_PARTIAL -> stringResource(R.string.feed_partially_dispensed, pharmacy)
        FeedKinds.MEDICINES_UNAVAILABLE -> stringResource(R.string.feed_unavailable, pharmacy)
        FeedKinds.TOKEN_CALLED -> item.subject?.let { stringResource(R.string.feed_token_called, it) }
            ?: stringResource(R.string.feed_token_called_no_number)
        FeedKinds.VISIT_COMPLETED -> stringResource(R.string.feed_visit_completed, doctor)
        else -> stringResource(R.string.feed_update)
    }
}

@Composable
fun documentKindLabel(kind: String?): String = when (kind) {
    DocumentKinds.PRESCRIPTION -> stringResource(R.string.kind_prescription)
    DocumentKinds.VISIT_SUMMARY -> stringResource(R.string.kind_visit_summary)
    DocumentKinds.INVESTIGATIONS -> stringResource(R.string.kind_investigations)
    DocumentKinds.ADVICE_REFERRAL -> stringResource(R.string.kind_advice_referral)
    else -> stringResource(R.string.kind_other)
}

@Composable
private fun FeedRow(item: FeedItemEntity, onClick: () -> Unit) {
    val (icon, tint) = when (item.kind) {
        FeedKinds.DOCUMENT_NEW -> Icons.Filled.Edit to MaterialTheme.colorScheme.primary
        FeedKinds.DOCUMENT_AMENDED -> Icons.Filled.Edit to Amber
        // Neutral icon: "ready" says nothing about whether the result is within range.
        FeedKinds.LAB_RESULT_READY -> Icons.AutoMirrored.Filled.List to MaterialTheme.colorScheme.primary
        FeedKinds.MEDICINES_DISPENSED, FeedKinds.MEDICINES_PARTIAL, FeedKinds.MEDICINES_UNAVAILABLE ->
            Icons.Filled.ShoppingCart to MaterialTheme.colorScheme.secondary
        FeedKinds.TOKEN_CALLED -> Icons.Filled.Notifications to OkGreen
        else -> Icons.Filled.Info to MaterialTheme.colorScheme.secondary
    }
    ListItem(
        headlineContent = { Text(feedTitle(item)) },
        supportingContent = { Text(formatDateTime(item.occurredAt)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        trailingContent = {
            if (!item.read) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.badge_new), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("feed_item"),
    )
}
