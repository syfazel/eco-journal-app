package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.InviteGate
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/**
 * First-run invite gate (M7). The feed stays locked until a valid
 * code is entered. The code is hashed on the device and only the
 * hash is compared/stored — the code never leaves the device.
 * On mismatch a fixed error is shown, never a hint about which
 * code is valid. This is a pilot-distribution control, not a
 * security boundary against a determined APK holder (see
 * InviteGate); the screen says so honestly in its subtitle.
 */
@Composable
fun InviteScreen(
    repo: EdgeRepository,
    store: SettingsStore,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontFamily = EcoFontFamily,
            fontSize = 32.sp * fontScale,
        )
        NewspaperDoubleRule()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.invite_title),
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.invite_subtitle),
            fontFamily = EcoFontFamily,
            fontSize = 15.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; error = false },
            label = {
                Text(
                    text = stringResource(R.string.invite_code_label),
                    fontFamily = EcoFontFamily,
                )
            },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            enabled = !checking && code.isNotBlank(),
            onClick = {
                checking = true
                scope.launch {
                    val edgeInvites = InviteGate.metaHashes(
                        repo.dao.metaValue("edge_invite_hashes"))
                    val edgeRevocations = InviteGate.metaHashes(
                        repo.dao.metaValue("edge_revocations"))
                    val hash = InviteGate.verifyCode(
                        context, code, edgeInvites, edgeRevocations)
                    if (hash != null) {
                        store.setInviteHash(hash)
                        store.setInviteOk(true)
                    } else {
                        error = true
                    }
                    checking = false
                }
            },
        ) {
            Text(
                text = stringResource(R.string.invite_confirm),
                fontFamily = EcoFontFamily,
            )
        }
        if (error) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.invite_error),
                fontFamily = EcoFontFamily,
                fontSize = 15.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.invite_privacy_note),
            fontFamily = EcoFontFamily,
            fontSize = 13.sp * fontScale,
        )
    }
}
