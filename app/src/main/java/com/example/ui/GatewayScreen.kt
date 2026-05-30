package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.GatewayLog
import com.example.data.ModelInfo
import com.example.data.ModelsRegistry
import com.example.data.ProxySetting
import com.example.server.ModelRouter
import com.example.server.ServerStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Cosmic Midnight Theme Palette Details
val CosmicDark = Color(0xFF0B0C15)
val MetallicTeal = Color(0xFF0E1B1D)
val NeonCyan = Color(0xFF00E6FF)
val DeepCyanAccent = Color(0xFF082D33)
val BorderSlate = Color(0xFF1B2A30)
val GhostText = Color(0xFF80959C)

@Composable
fun GatewayScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier
) {
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val activePort by viewModel.activePort.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val rawLogs by viewModel.logsState.collectAsStateWithLifecycle()
    val downloadProgresses by viewModel.downloadProgresses.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) }
    var selectedLogDetail by remember { mutableStateOf<GatewayLog?>(null) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(CosmicDark),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CosmicDark)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Router Icon",
                        tint = NeonCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AETHER INTEL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "AI Proxy Gateway",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Small floating setting icon
                    IconButton(
                        onClick = { showApiKeyDialog = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MetallicTeal)
                            .size(40.dp)
                            .testTag("open_key_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "API Keys Configuration",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Tab Selection Layout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MetallicTeal)
                        .padding(4.dp)
                ) {
                    TabPillButton(
                        text = "Dashboard",
                        isActive = activeTab == 0,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_dashboard"),
                        onClick = { activeTab = 0 }
                    )
                    TabPillButton(
                        text = "Models & Logs",
                        isActive = activeTab == 1,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_models_logs"),
                        onClick = { activeTab = 1 }
                    )
                }
            }
        },
        bottomBar = {
            // Keep safe bar padding for notch/system gesture navigation
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(CosmicDark)
        ) {
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "MainTabTransition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> DashboardTab(
                        serverStatus = serverStatus,
                        activePort = activePort,
                        errorCount = errorCount,
                        settings = settings,
                        downloadedModels = downloadedModels,
                        onToggleServer = {
                            if (serverStatus == ServerStatus.RUNNING) {
                                viewModel.stopServer()
                            } else {
                                viewModel.startServer()
                            }
                        },
                        onPortChanged = { newPort ->
                            viewModel.updatePort(newPort)
                        },
                        onHardwareChanged = { npu, bypassGpu ->
                            viewModel.updateHardwareConfigs(npu, bypassGpu)
                        }
                    )
                    1 -> ModelsAndLogsTab(
                        rawLogs = rawLogs,
                        downloadProgresses = downloadProgresses,
                        downloadedModels = downloadedModels,
                        searchQuery = searchQuery,
                        onSearchChange = { viewModel.updateQuery(it) },
                        onClearLogs = { viewModel.clearLogs() },
                        onDownloadModel = { viewModel.triggerModelDownload(it) },
                        onDeleteWeight = { viewModel.deleteModelWeight(it) },
                        onSelectLog = { selectedLogDetail = it }
                    )
                }
            }

            // Expanded Detail Modal Bottom Sheet for logs
            selectedLogDetail?.let { logDetail ->
                LogDetailBottomSheet(
                    log = logDetail,
                    onDismiss = { selectedLogDetail = null }
                )
            }

            // Alert API keys setup modal dialog
            if (showApiKeyDialog) {
                ApiKeySetupDialog(
                    initialKey = settings.geminiApiKey,
                    onSave = {
                        viewModel.updateApiKey(it)
                        showApiKeyDialog = false
                        Toast.makeText(context, "API Key configuration applied", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showApiKeyDialog = false }
                )
            }
        }
    }
}

@Composable
fun TabPillButton(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundByState = if (isActive) DeepCyanAccent else Color.Transparent
    val borderByState = if (isActive) BorderSlate else Color.Transparent
    val tintByState = if (isActive) NeonCyan else GhostText
    val weight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundByState)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = tintByState,
            fontSize = 14.sp,
            fontWeight = weight
        )
    }
}

@Composable
fun DashboardTab(
    serverStatus: ServerStatus,
    activePort: Int,
    errorCount: Int,
    settings: ProxySetting,
    downloadedModels: Set<String>,
    onToggleServer: () -> Unit,
    onPortChanged: (Int) -> Unit,
    onHardwareChanged: (Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // glowing status panel
        item {
            ServerStatusGlowCard(
                status = serverStatus,
                port = activePort,
                onToggle = onToggleServer
            )
        }

        // endpoints access link paths block
        item {
            EndpointsConfigCard(
                port = activePort,
                onCopy = { url ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("proxy_url", url))
                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Port and API Key quick warning panel
        item {
            PortConfigSliderCard(
                currentPort = settings.port,
                onPortSaved = onPortChanged
            )
        }

        // Hardware details acceleration widget
        item {
            HardwareAccCard(
                settings = settings,
                onToggleNpu = { onHardwareChanged(it, settings.bypassGpu) },
                onToggleBypassGpu = { onHardwareChanged(settings.enableNpuBackend, it) }
            )
        }

        // APK deployment warning regarding API credential leakage risk
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MetallicTeal),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Shield Warning Logo",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Security Warning",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "I have stored default API credentials in the generated application configurations. Please keep in mind that Android APK files can be easily decompiled, and sensitive variables can be parsed and extracted. Do not release this package publicly.",
                        fontSize = 12.sp,
                        color = GhostText,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ServerStatusGlowCard(
    status: ServerStatus,
    port: Int,
    onToggle: () -> Unit
) {
    val statusText = when (status) {
        ServerStatus.RUNNING -> "GATEWAY ACTIVE"
        ServerStatus.STOPPED -> "OFFLINE / PAUSED"
        ServerStatus.ERROR -> "PORT ASSIGN ERROR"
    }

    val glowColor = when (status) {
        ServerStatus.RUNNING -> NeonCyan
        ServerStatus.STOPPED -> Color(0xFFFFA726)
        ServerStatus.ERROR -> Color(0xFFFF5252)
    }

    // pulsing indicator animation
    val infiniteTransition = rememberInfiniteTransition(label = "indicatorRipple")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_glow_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            // Pulse circle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = 0.15f * alphaAnim)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(glowColor)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            
            Text(
                text = statusText,
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "Dynamic Socket Port: $port",
                fontSize = 12.sp,
                color = GhostText,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))
            
            val buttonColor = if (status == ServerStatus.RUNNING) Color(0xFFFF5252) else NeonCyan
            val buttonTxt = if (status == ServerStatus.RUNNING) "Deactivate Service" else "Launch Proxy Server"
            
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = CosmicDark
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("server_toggle_button")
            ) {
                Text(
                    text = buttonTxt,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun EndpointsConfigCard(
    port: Int,
    onCopy: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "LOCAL GATEWAY LINK ADDRESSES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            EndpointCopyRow(
                title = "1. Main Mock completions endpoint",
                url = "http://localhost:$port/v1/chat/completions",
                onCopy = onCopy,
                tagSuffix = "chat_completions"
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            EndpointCopyRow(
                title = "2. Catalog listing endpoint",
                url = "http://localhost:$port/v1/models",
                onCopy = onCopy,
                tagSuffix = "models"
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeepCyanAccent)
                    .padding(12.dp)
            ) {
                Text(
                    text = "Insert either URL directly into OpenAI client SDK scripts or tools (e.g., Cursor, LibreChat) as the BASE_URL to route queries through your on-phone LLM models!",
                    fontSize = 11.sp,
                    color = GhostText,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun EndpointCopyRow(
    title: String,
    url: String,
    onCopy: (String) -> Unit,
    tagSuffix: String
) {
    Column {
        Text(text = title, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CosmicDark)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = url,
                fontSize = 12.sp,
                color = NeonCyan,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onCopy(url) },
                modifier = Modifier
                    .size(36.dp)
                    .testTag("copy_url_button_$tagSuffix")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Copy URL",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun PortConfigSliderCard(
    currentPort: Int,
    onPortSaved: (Int) -> Unit
) {
    var portText by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "HTTP ACCESS ROUTER PORT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { char -> char.isDigit() } },
                    label = { Text("Port", color = GhostText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = BorderSlate,
                        focusedContainerColor = CosmicDark,
                        unfocusedContainerColor = CosmicDark
                    ),
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val port = portText.toIntOrNull() ?: 8080
                            onPortSaved(port)
                            keyboardController?.hide()
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("port_input")
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val port = portText.toIntOrNull() ?: 8080
                        onPortSaved(port)
                        keyboardController?.hide()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCyanAccent,
                        contentColor = NeonCyan
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .testTag("port_save_button")
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
fun HardwareAccCard(
    settings: ProxySetting,
    onToggleNpu: (Boolean) -> Unit,
    onToggleBypassGpu: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "HARDWARE PLATFORM ACCELERATORS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable NPU Cores",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Boosts inference using neural processors where available",
                        fontSize = 11.sp,
                        color = GhostText
                    )
                }
                Switch(
                    checked = settings.enableNpuBackend,
                    onCheckedChange = onToggleNpu,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CosmicDark,
                        checkedTrackColor = NeonCyan,
                        uncheckedThumbColor = GhostText,
                        uncheckedTrackColor = CosmicDark
                    ),
                    modifier = Modifier.testTag("npu_switch")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bypass GPU Drivers",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Forces CPU execution block if GPU kernels raise compile exceptions",
                        fontSize = 11.sp,
                        color = GhostText
                    )
                }
                Switch(
                    checked = settings.bypassGpu,
                    onCheckedChange = onToggleBypassGpu,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CosmicDark,
                        checkedTrackColor = NeonCyan,
                        uncheckedThumbColor = GhostText,
                        uncheckedTrackColor = CosmicDark
                    ),
                    modifier = Modifier.testTag("gpu_bypass_switch")
                )
            }
        }
    }
}

@Composable
fun ModelsAndLogsTab(
    rawLogs: List<GatewayLog>,
    downloadProgresses: Map<String, Int>,
    downloadedModels: Set<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearLogs: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onDeleteWeight: (String) -> Unit,
    onSelectLog: (GatewayLog) -> Unit
) {
    var subTabState by remember { mutableStateOf(0) } // 0 = logs, 1 = model library

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // sub tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { subTabState = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTabState == 0) DeepCyanAccent else MetallicTeal,
                    contentColor = if (subTabState == 0) NeonCyan else Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).testTag("subtab_logs")
            ) {
                Text("Audit Logs (${rawLogs.size})")
            }
            Button(
                onClick = { subTabState = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTabState == 1) DeepCyanAccent else MetallicTeal,
                    contentColor = if (subTabState == 1) NeonCyan else Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).testTag("subtab_models")
            ) {
                Text("Models Library")
            }
        }

        if (subTabState == 0) {
            // Logs section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = GhostText) },
                    placeholder = { Text("Filter logs...", color = GhostText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = BorderSlate,
                        focusedContainerColor = MetallicTeal,
                        unfocusedContainerColor = MetallicTeal
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("logs_filter_input"),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MetallicTeal)
                        .size(54.dp)
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = Color(0xFFFF5252)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (rawLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Spyglass",
                            tint = BorderSlate,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No request logs traced yet.",
                            color = GhostText,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Ping 'http://localhost:[port]' to view traces",
                            color = GhostText.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(rawLogs) { log ->
                        LogItemRow(log = log, onClick = { onSelectLog(log) })
                    }
                }
            }
        } else {
            // Models Library subtab
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Text(
                        text = "ON-DEVICE INFERENCE MODELS (LiteRT)",
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                
                items(ModelsRegistry.localModels) { model ->
                    LocalModelCard(
                        model = model,
                        progress = downloadProgresses[model.id],
                        isDownloaded = downloadedModels.contains(model.id),
                        onDownload = { onDownloadModel(model.id) },
                        onDelete = { onDeleteWeight(model.id) }
                    )
                }

                item {
                    HorizontalDivider(color = BorderSlate, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))
                }

                item {
                    Text(
                        text = "CLOUD TRANSLATOR PROXIES (Google AI Studio)",
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                items(ModelsRegistry.cloudModels) { model ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Cloud Icon",
                                tint = NeonCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = model.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                Text(text = model.description, color = GhostText, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: GatewayLog, onClick: () -> Unit) {
    val is2xx = log.statusCode in 200..299
    val statusColor = if (is2xx) Color(0xFF00E6FF) else Color(0xFFFF5252)

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                Text(
                    text = log.method ?: "UNKNOWN",
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    color = statusColor
                )
                Text(
                    text = log.statusCode.toString(),
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.sp,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.endpoint ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = ModelRouter.getDisplayName(log.modelUsed ?: "unknown"),
                        fontSize = 10.sp,
                        color = GhostText
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "•",
                        fontSize = 10.sp,
                        color = BorderSlate
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${log.latencyMs} ms",
                        fontSize = 10.sp,
                        color = NeonCyan
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = dateFormat.format(Date(log.timestamp)),
                color = GhostText.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun LocalModelCard(
    model: ModelInfo,
    progress: Int?,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MetallicTeal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "On device storage icon",
                    tint = if (isDownloaded) NeonCyan else GhostText,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = model.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BorderSlate)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "${model.sizeGb} GB", color = GhostText, fontSize = 9.sp)
                        }
                    }
                    Text(text = model.description, color = GhostText, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (progress != null) {
                // Downloading action active
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Downloading kernels...", color = NeonCyan, fontSize = 11.sp)
                        Text(text = "$progress%", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        color = NeonCyan,
                        trackColor = CosmicDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isDownloaded) {
                        OutlinedButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF5252)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("delete_weights_${model.id}")
                        ) {
                            Text("Clear Cache", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCyanAccent,
                                contentColor = NeonCyan
                            ),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("download_weights_${model.id}")
                        ) {
                            Text("Install weights", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailBottomSheet(
    log: GatewayLog,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CosmicDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = BorderSlate) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${log.method ?: "UNKNOWN"} API TRACE",
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Status: ${log.statusCode}",
                    color = if (log.statusCode in 200..299) NeonCyan else Color(0xFFFF5252),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = log.endpoint ?: "",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "Model Routed: ${ModelRouter.getDisplayName(log.modelUsed ?: "unknown")} (${log.latencyMs} ms latency)",
                fontSize = 12.sp,
                color = GhostText,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Body logs snippets
            CodeBlockField(title = "Request Payload Input Snippet", codeText = log.requestSnippet ?: "")
            Spacer(modifier = Modifier.height(16.dp))
            CodeBlockField(title = "Response Payload Output Snippet", codeText = log.responseSnippet ?: "")

            if (log.errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Error Stack details:", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(text = log.errorMessage, color = Color.White, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MetallicTeal, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close Details")
            }
        }
    }
}

@Composable
fun CodeBlockField(
    title: String,
    codeText: String
) {
    Column {
        Text(text = title, fontSize = 11.sp, color = GhostText, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MetallicTeal)
                .padding(12.dp)
        ) {
            Text(
                text = codeText.ifEmpty { "Empty Payload Text" },
                color = NeonCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun ApiKeySetupDialog(
    initialKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyText by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Google AI Studio API Secrets",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = "If you are running the gateway proxy over an external local area network (from a computer, cursor client or other device), you can override the built-in Studio keys by configuring your custom keys below:",
                    color = GhostText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Gemini API Key Override", color = GhostText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = BorderSlate,
                        focusedContainerColor = CosmicDark,
                        unfocusedContainerColor = CosmicDark
                    ),
                    shape = RoundedCornerShape(6.dp),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().testTag("api_key_override_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(keyText) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = CosmicDark),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("apply_key_button")
            ) {
                Text("Apply Override")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text("Cancel")
            }
        },
        containerColor = MetallicTeal
    )
}
