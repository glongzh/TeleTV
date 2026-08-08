package com.teletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.teletv.R
import com.teletv.ServiceLocator
import com.teletv.proxy.ProxyInfo
import com.teletv.proxy.ProxyKind
import com.teletv.ui.theme.TeleTvColors
import com.teletv.ui.theme.TeleTvDimens
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Steps of the proxy flow: the list of saved proxies, or the add form. */
private sealed interface ProxyStep {
    data object List : ProxyStep
    data object Add : ProxyStep
}

/**
 * Proxy configuration, reachable both before login (a blocked network stalls the
 * app pre-auth) and from settings. Lists saved proxies with the active one
 * marked, plus Direct connection and Add; the add form enters an MTProto or
 * SOCKS5 proxy via the TV IME. Backed by [ServiceLocator.proxy]; TDLib persists.
 */
@Composable
fun ProxyScreen(onDone: () -> Unit) {
    var step by remember { mutableStateOf<ProxyStep>(ProxyStep.List) }

    when (step) {
        is ProxyStep.List -> ProxyList(
            onAdd = { step = ProxyStep.Add },
            onDone = onDone,
        )
        is ProxyStep.Add -> ProxyAddForm(onDone = { step = ProxyStep.List })
    }
}

@Composable
private fun ProxyList(onAdd: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var proxies by remember { mutableStateOf<List<ProxyInfo>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var testingId by remember { mutableStateOf<Int?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun reload() {
        scope.launch { proxies = runCatching { ServiceLocator.proxy.list() }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(Unit) { reload() }
    BackHandler { onDone() }

    val anyEnabled = proxies?.any { it.enabled } == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop)
            .padding(48.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        // Direct connection (disable all proxies).
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { ServiceLocator.proxy.disableAll() }
                    busy = false
                    reload()
                }
            },
            modifier = Modifier.width(420.dp).padding(vertical = 6.dp).focusRequester(firstFocus),
        ) {
            val prefix = if (!anyEnabled) "✓ " else ""
            Text(prefix + stringResource(R.string.proxy_direct))
        }

        proxies?.forEach { p ->
            val label = "${if (p.enabled) "✓ " else ""}${p.kind.name}  ${p.server}:${p.port}"
            Row(
                modifier = Modifier.width(420.dp).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { ServiceLocator.proxy.enable(p.id) }
                            busy = false
                            reload()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        testingId = p.id
                        message = null
                        scope.launch {
                            runCatching { ServiceLocator.proxy.ping(p.id) }
                                .onSuccess { s -> message = latencyText(s) }
                                .onFailure { message = failureText(it) }
                            testingId = null
                            busy = false
                        }
                    },
                ) { TestButtonLabel(testing = testingId == p.id) }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { ServiceLocator.proxy.remove(p.id) }
                            busy = false
                            reload()
                        }
                    },
                ) { Text(stringResource(R.string.proxy_remove)) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAdd,
            modifier = Modifier.width(420.dp).padding(vertical = 6.dp),
        ) { Text(stringResource(R.string.proxy_add)) }

        Box(modifier = Modifier.height(28.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TeleTvColors.Muted) }
        }
    }

    LaunchedEffect(Unit) { firstFocus.requestFocus() }
}

@Composable
private fun ProxyAddForm(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(ProxyKind.SOCKS5) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val serverFocus = remember { FocusRequester() }

    fun portNum(): Int? = port.toIntOrNull()

    BackHandler { onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleTvColors.BrandBackdrop)
            .padding(horizontal = 72.dp, vertical = 36.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.proxy_add_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // Type selector.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { kind = ProxyKind.SOCKS5 }, modifier = Modifier.width(200.dp)) {
                Text((if (kind == ProxyKind.SOCKS5) "✓ " else "") + stringResource(R.string.proxy_type_socks5))
            }
            Button(onClick = { kind = ProxyKind.MTPROTO }, modifier = Modifier.width(200.dp)) {
                Text((if (kind == ProxyKind.MTPROTO) "✓ " else "") + stringResource(R.string.proxy_type_mtproto))
            }
        }
        Spacer(Modifier.height(16.dp))

        ProxyField(stringResource(R.string.proxy_server), server, { server = it }, serverFocus)
        ProxyField(stringResource(R.string.proxy_port), port, { port = it }, numeric = true)

        if (kind == ProxyKind.MTPROTO) {
            ProxyField(stringResource(R.string.proxy_secret), secret, { secret = it }, masked = true)
        } else {
            ProxyField(stringResource(R.string.proxy_username), username, { username = it })
            ProxyField(stringResource(R.string.proxy_password), password, { password = it }, masked = true)
        }

        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TeleTvColors.Muted) }
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !busy && server.isNotBlank() && portNum() != null,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching {
                            val p = portNum()!!
                            if (kind == ProxyKind.MTPROTO) {
                                ServiceLocator.proxy.addMtproto(server, p, secret, enable = true)
                            } else {
                                ServiceLocator.proxy.addSocks5(server, p, username, password, enable = true)
                            }
                        }.onSuccess { onDone() }
                            .onFailure { message = failureText(it); busy = false }
                    }
                },
            ) { Text(stringResource(R.string.proxy_save)) }
            Button(
                enabled = !busy && server.isNotBlank() && portNum() != null,
                onClick = {
                    busy = true
                    testing = true
                    message = null
                    scope.launch {
                        val p = portNum()!!
                        runCatching {
                            if (kind == ProxyKind.MTPROTO) ServiceLocator.proxy.pingMtproto(server, p, secret)
                            else ServiceLocator.proxy.pingSocks5(server, p, username, password)
                        }.onSuccess { s -> message = latencyText(s) }
                            .onFailure { message = failureText(it) }
                        testing = false
                        busy = false
                    }
                },
            ) { TestButtonLabel(testing = testing) }
            Button(enabled = !busy, onClick = onDone) { Text(stringResource(R.string.cancel)) }
        }
    }

    LaunchedEffect(Unit) { serverFocus.requestFocus() }
}

@Composable
private fun ProxyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
    numeric: Boolean = false,
    masked: Boolean = false,
) {
    Column(modifier = Modifier.width(420.dp).padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TeleTvColors.Muted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(TeleTvDimens.RadiusChip))
                .background(TeleTvColors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TeleTvColors.OnBg),
                cursorBrush = SolidColor(TeleTvColors.Accent),
                singleLine = true,
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else if (masked) KeyboardType.Password else KeyboardType.Text,
                ),
            )
        }
    }
}

/** Test button content: a spinner + "Testing…" while a ping is in flight, else "Test". */
@Composable
private fun TestButtonLabel(testing: Boolean) {
    if (testing) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spinner(size = 16.dp)
        }
    } else {
        Text(stringResource(R.string.proxy_test))
    }
}

private fun latencyText(seconds: Double): String = "OK · ${(seconds * 1000).toInt()} ms"

private fun failureText(e: Throwable): String = e.message ?: "Failed"
