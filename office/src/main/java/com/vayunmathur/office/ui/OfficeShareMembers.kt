package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.office.platform.OfficeMember
import com.vayunmathur.office.platform.OfficeRoles

import com.vayunmathur.office.R
/** Members list with role management (split from OfficeOnlineScreens.kt). */

@Composable
internal fun ShareMembersList(
    members: List<OfficeMember>,
    deviceId: String,
    isOwner: Boolean,
    memberMenu: String?,
    onMemberMenu: (String?) -> Unit,
    onSetRole: (String, String) -> Unit,
    onTransferOwner: (String) -> Unit,
) {
if (members.isNotEmpty()) {
    Text(stringResource(R.string.people_with_access), style = MaterialTheme.typography.labelMedium)
    members.filter { it.role != OfficeRoles.REVOKED }.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val displayName = if (m.name.isNotBlank()) m.name else m.id.take(8)
            val label = if (m.id == deviceId) "$displayName (you)" else displayName
            Text(
                "• $label — ${m.role}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            if (isOwner && m.id != deviceId) {
                Box {
                    TextButton(onClick = { onMemberMenu(m.id) }) {
                        Text(stringResource(R.string.manage))
                    }
                    DropdownMenu(
                        expanded = memberMenu == m.id,
                        onDismissRequest = { onMemberMenu(null) }) {
                        fun setRole(role: String) {
                            onMemberMenu(null)
                            onSetRole(m.id, role)
                        }
                        if (m.role == OfficeRoles.EDITOR) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.make_viewer)) },
                                onClick = { setRole(OfficeRoles.VIEWER) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.make_editor)) },
                                onClick = { setRole(OfficeRoles.EDITOR) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.make_owner)) },
                            onClick = { onMemberMenu(null); onTransferOwner(m.id) })
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.remove)) },
                            onClick = { setRole(OfficeRoles.REVOKED) },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(SpacingSm))
}
}
