package com.openclaw.assistant.ui.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendManager
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home connections card, driven by the backends that are actually
 * configured: one tile per enabled backend (primary first), instead of the
 * upstream hardcoded OpenClaw + Hermes product tiles. A fresh CTB install
 * shows a single "Telegram Bridge (CTB)" tile whose status comes from
 * `GET /healthz` — never from gateway connectivity, and never a chat ping.
 */
@Composable
fun PrimaryBackendCard(
    gatewayConnected: Boolean = false,
    onGatewayTest: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = remember { BackendManager.getInstance(context) }
    val backends by manager.backends.collectAsState()
    val visible = remember(backends) {
        backends.filter { it.enabled }.sortedByDescending { it.isPrimary }.take(3)
    }
    var testResults by remember { mutableStateOf<Map<String, ConnectionTestResult>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val connectionTestingText = stringResource(R.string.av_connection_testing)

    suspend fun probe(config: AgentBackendConfig) {
        testResults = testResults + (config.id to ConnectionTestResult(false, connectionTestingText))
        val result = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
        testResults = testResults + (config.id to result)
    }

    // HTTP (CTB) and Hermes backends are probed on entry; the healthz probe
    // is side-effect free. Gateway status comes from the runtime connection.
    LaunchedEffect(visible.map { it.id to it.updatedAt }) {
        visible.filter { it.type != BackendType.OPENCLAW_GATEWAY }.forEach { probe(it) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.av_home_connections_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.av_home_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    visible.forEach { backend ->
                        val isGateway = backend.type == BackendType.OPENCLAW_GATEWAY
                        val test = testResults[backend.id]
                        val testing = !isGateway && test?.message == connectionTestingText
                        val connected = if (isGateway) gatewayConnected else test?.ok == true
                        BackendProductTile(
                            name = backend.tileLabel(),
                            connected = connected,
                            testing = testing,
                            statusText = when {
                                connected -> stringResource(R.string.av_home_connected)
                                testing -> connectionTestingText
                                !isGateway && test != null -> test.message
                                else -> stringResource(R.string.av_home_disconnected)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Button(
                    onClick = {
                        if (visible.any { it.type == BackendType.OPENCLAW_GATEWAY }) {
                            onGatewayTest?.invoke()
                        }
                        scope.launch {
                            visible.filter { it.type != BackendType.OPENCLAW_GATEWAY }
                                .forEach { probe(it) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.av_home_test_connection))
                }
            }
        }
    }
}

/**
 * User-facing tile label. Legacy migrated configs carry upstream product
 * names ("OpenClaw HTTP"); the CTB build shows the Telegram Bridge label
 * instead of upstream branding.
 */
@Composable
private fun AgentBackendConfig.tileLabel(): String = when (type) {
    BackendType.OPENCLAW_HTTP ->
        displayName.takeUnless { it.isBlank() || it.startsWith("OpenClaw") }
            ?: stringResource(R.string.ctb_backend_display_name)
    BackendType.HERMES_API_SERVER ->
        displayName.ifBlank { stringResource(R.string.setup_mode_hermes) }
    BackendType.OPENCLAW_GATEWAY ->
        displayName.ifBlank { stringResource(R.string.setup_mode_gateway) }
}

@Composable
private fun BackendProductTile(
    name: String,
    connected: Boolean,
    testing: Boolean = false,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    val statusColor = when {
        connected -> Color(0xFF34C759)
        testing -> Color(0xFFFFA000)
        else -> Color(0xFFE53935)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(shape = CircleShape, colors = CardDefaults.cardColors(containerColor = statusColor), modifier = Modifier.size(10.dp)) {}
                Spacer(modifier = Modifier.size(8.dp))
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
