package com.samford.patient.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samford.patient.R
import com.samford.patient.core.db.ProfileEntity

@Composable
fun ProfileAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun profileSubtitle(profile: ProfileEntity): String {
    val parts = buildList {
        profile.ageYears?.let { age -> add(pluralStringResource(R.plurals.profile_age, age, age) + (profile.sex?.let { " · ${sexLabel(it)}" } ?: "")) }
        profile.uhid?.let { add(stringResource(R.string.profile_uhid, it)) }
    }
    return parts.joinToString(" · ")
}

@Composable
fun sexLabel(sex: String): String = when (sex.uppercase()) {
    "FEMALE", "F" -> stringResource(R.string.sex_female)
    "MALE", "M" -> stringResource(R.string.sex_male)
    "OTHER" -> stringResource(R.string.sex_other)
    else -> sex
}

/** Family profiles linked to the same mobile. Switching changes which records every screen shows. */
@Composable
fun ProfileSwitcher(
    profiles: List<ProfileEntity>,
    active: ProfileEntity?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (active == null) return
    var open by remember { mutableStateOf(false) }
    val switchLabel = stringResource(R.string.profile_switch)
    Box(modifier) {
        TextButton(
            onClick = { if (profiles.size > 1) open = true },
            modifier = Modifier
                .testTag("profile_switcher")
                .semantics { contentDescription = "$switchLabel: ${active.displayName}" },
        ) {
            ProfileAvatar(active.displayName)
            Spacer(Modifier.width(8.dp))
            Text(active.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            if (profiles.size > 1) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(profileSubtitle(profile), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    leadingIcon = { ProfileAvatar(profile.displayName) },
                    trailingIcon = { if (profile.id == active.id) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(profile.id)
                    },
                    modifier = Modifier.testTag("profile_${profile.id}"),
                )
            }
        }
    }
}

@Composable
fun ProfileRow(profile: ProfileEntity, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(profile.displayName)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(profile.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(profileSubtitle(profile), style = MaterialTheme.typography.bodySmall)
        }
    }
}
