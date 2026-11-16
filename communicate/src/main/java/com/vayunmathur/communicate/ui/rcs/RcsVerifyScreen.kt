package com.vayunmathur.communicate.ui.rcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Safety-number verification for an RCS E2EE peer (§5.4).
 *
 * Shows our safety number (our latest published key package) next to the
 * peer's (their latest cached package). Matching numbers (compared
 * out-of-band, e.g. voice call) mean no MITM on the key directory. Marking
 * verified records the package sketch — a later package change raises the
 * re-key warning and clears verification.
 *
 * Single public `@Composable`.
 */
@Composable
fun RcsVerifyScreen(
    peerE164: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val state by produceState<Triple<String?, String?, Boolean>?>(initialValue = null, peerE164, refresh) {
        value = withContext(Dispatchers.IO) {
            val local = RcsE2E.localE164(context) ?: return@withContext null
            val mine = RcsE2E.mySafetyFingerprint(local)
            val theirs = RcsE2E.safetyFingerprint(peerE164)
            val verified = RcsE2E.isVerified(context, peerE164)
            Triple(mine, theirs, verified)
        }
    }
    DetailScaffold(
        title = stringResource(R.string.rcs_verify_title),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    content = { Text("Your safety number", fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(state?.first ?: "No published key yet — start an encrypted chat first")
                    },
                )
                ListItem(
                    content = { Text(peerE164, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(state?.second ?: "No key received yet — ask them to start an encrypted chat")
                    },
                )
                ListItem(
                    content = { Text("Status", fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(if (state?.third == true) "Verified ✓" else "Not verified")
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val pkg = RcsPeerKeys.get(peerE164) ?: return@withContext false
                                    RcsE2E.setVerifiedWithPackage(context, peerE164, pkg)
                                    true
                                }
                                AppMessages.show(
                                    if (ok) context.getString(R.string.rcs_verify_done)
                                    else context.getString(R.string.rcs_verify_no_key),
                                )
                                refresh++
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rcs_verify_mark))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    RcsE2E.setVerified(context, peerE164, false)
                                }
                                AppMessages.show(context.getString(R.string.rcs_verify_cleared))
                                refresh++
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rcs_verify_clear))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
