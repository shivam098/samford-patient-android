package com.samford.patient.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.samford.patient.data.RecordsRepository
import com.samford.patient.data.VisitRecord
import com.samford.patient.ui.common.EmptyState
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.common.ProfileSwitcher
import com.samford.patient.ui.common.SampleBanner
import com.samford.patient.ui.common.WarningBanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModel(private val container: AppContainer) : ViewModel() {
    private val records = container.records
    val profiles = records.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active = records.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val timeline = records.activeProfile
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else records.timeline(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sync = container.syncCoordinator.status

    fun select(id: String) {
        viewModelScope.launch { records.setActiveProfile(id) }
    }

    fun refresh() {
        viewModelScope.launch { container.syncCoordinator.run(force = true, reason = "manual") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    onOpenDocument: (String) -> Unit,
    onOpenLabResult: (String) -> Unit,
    onOpenVisit: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: RecordsViewModel = viewModel { RecordsViewModel(container) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val sample = LocalSampleMode.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.records_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    .testTag("records_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sample) item { SampleBanner() }
                val allergies = RecordsRepository.allergies(active)
                if (allergies.isNotEmpty()) {
                    item {
                        WarningBanner(
                            text = stringResource(
                                R.string.records_allergies,
                                allergies.joinToString("; ") { a -> listOfNotNull(a.substance, a.reaction).joinToString(": ") },
                            ),
                            modifier = Modifier.testTag("allergies_banner"),
                        )
                    }
                }
                val list: List<VisitRecord>? = timeline
                when {
                    list == null -> Unit
                    list.isEmpty() -> item {
                        EmptyState(stringResource(R.string.records_empty_title), stringResource(R.string.records_empty_body))
                    }
                    else -> items(list, key = { it.visit.id }) { record ->
                        VisitCard(record, expanded = false, onOpenDocument, onOpenLabResult, onOpenVisit)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitScreen(
    visitId: String,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenLabResult: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val flow = remember(visitId) { container.records.visit(visitId) }
    val record by flow.collectAsStateWithLifecycle(initialValue = null)
    val sample = LocalSampleMode.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sample) item { SampleBanner() }
            record?.let { r ->
                item { VisitCard(r, expanded = true, onOpenDocument, onOpenLabResult, onOpenVisit = null) }
            }
        }
    }
}
