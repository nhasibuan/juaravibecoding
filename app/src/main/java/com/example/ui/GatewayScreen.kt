package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.GatewayLog
import com.example.data.LocalModelInfo
import com.example.data.ModelsRegistry
import com.example.data.ProxySetting
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GatewayScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Observe state variables safely
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val logs by viewModel.logsState.collectAsStateWithLifecycle()
    val isRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val activePort by viewModel.serverPort.collectAsStateWithLifecycle()
    val serverIp by viewModel.serverIp.collectAsStateWithLifecycle()

    // Temporary states to allow comfortable key-in
    var portValue by remember { mutableStateOf("8080") }
    var apiKeyValue by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    // Sync input states when configuration loads up from DB
    LaunchedEffect(settings) {
        settings?.let {
            portValue = it.port.toString()
            apiKeyValue = it.proxyApiKey
        }
    }

    val baseEndpoint = "http://$serverIp:$activePort"
    val chatEndpoint = "http://$serverIp:$activePort/v1/chat/completions"

    // Theme values (Deep cosmic dark palette)
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F172A), // Slate 900
            Color(0xFF020617)  // Slate 950
        )
    )

    val surfaceCardColor = Color(0xFF1E293B) // Slate 800
    val accentCyan = Color(0xFF06B6D4) // Cyan 500
    val accentGreen = Color(0xFF10B981) // Emerald 500
    val accentRed = Color(0xFFEF4444) // Red 500

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush = backgroundBrush)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Proxy Icon",
                        tint = accentCyan,
                        modifier = Modifier
                            .size(36.dp)
                            .padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = "AI PROXY GATEWAY",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "OpenAI-Compatible Local Translation Server",
                            fontSize = 12.sp,
                            color = Color.LightGray.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Status Console Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_status_card"),
                    colors = CardDefaults.cardColors(containerColor = surfaceCardColor),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Status indicator banner
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isRunning) accentGreen else accentRed)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRunning) "SERVER: ONLINE" else "SERVER: OFFLINE",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isRunning) accentGreen else accentRed,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            IconButton(
                                onClick = { viewModel.refreshIp() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Address",
                                    tint = accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Endpoints Displays
                        Text(
                            text = "COMPATIBLE BASE URL",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray.copy(alpha = 0.7f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = baseEndpoint,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "COPY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentCyan,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(baseEndpoint))
                                        Toast.makeText(context, "Copied Base URL!", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "CHAT COMPLETION ENDPOINT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray.copy(alpha = 0.7f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chatEndpoint,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = accentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "COPY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentCyan,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(chatEndpoint))
                                        Toast.makeText(context, "Copied endpoint URL!", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Server start/stop action buttons
                        Button(
                            onClick = { viewModel.toggleServer() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("server_toggle_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) accentRed else accentGreen
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = "Toggle Server",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "STOP SERVER ENGINE" else "START GATEWAY SERVER",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Gateway Configurations Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = surfaceCardColor),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "SERVER PARAMETERS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentCyan,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Routing Mode selection
                        Text(
                            text = "Routing Model Provider Strategy",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val activeProvider = settings?.targetProvider ?: "CLOUD_GEMINI"

                            Button(
                                onClick = { viewModel.changeProvider("CLOUD_GEMINI") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeProvider == "CLOUD_GEMINI") accentCyan else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Cloud Gemini API", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.changeProvider("LOCAL_VAL") },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeProvider == "LOCAL_VAL") accentCyan else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Local Simulator", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Port field
                        OutlinedTextField(
                            value = portValue,
                            onValueChange = { portValue = it },
                            label = { Text("Listening Port") },
                            placeholder = { Text("e.g. 8080") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentCyan,
                                unfocusedBorderColor = Color.LightGray.copy(alpha = 0.3f),
                                focusedLabelColor = accentCyan,
                                unfocusedLabelColor = Color.LightGray
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("port_settings_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // API Key Guard field
                        OutlinedTextField(
                            value = apiKeyValue,
                            onValueChange = { apiKeyValue = it },
                            label = { Text("OAuth / Client Proxy API Key (Optional)") },
                            placeholder = { Text("Set API key to restrict client queries") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(
                                        text = if (showApiKey) "HIDE" else "SHOW",
                                        color = accentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentCyan,
                                unfocusedBorderColor = Color.LightGray.copy(alpha = 0.3f),
                                focusedLabelColor = accentCyan,
                                unfocusedLabelColor = Color.LightGray
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_settings_input")
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                viewModel.applySettings(
                                    portText = portValue,
                                    apiKeyText = apiKeyValue,
                                    activeModelId = settings?.activeModelId ?: "litert-community/gemma-4-E2B-it-litert-lm",
                                    provider = settings?.targetProvider ?: "CLOUD_GEMINI"
                                )
                                Toast.makeText(context, "Proxy parameters updated!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentCyan),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Apply settings")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SAVE & DETACH SOCKET", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Allowed Models Card List
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ON-DEVICE GATED MODELS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentCyan,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ModelsRegistry.allowedModels.forEach { model ->
                        val isCurrentlySelected = settings?.activeModelId == model.modelId
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    viewModel.changeActiveModel(model.modelId)
                                    Toast.makeText(context, "Selected ${model.name}", Toast.LENGTH_SHORT).show()
                                }
                                .border(
                                    width = if (isCurrentlySelected) 2.dp else 0.dp,
                                    color = if (isCurrentlySelected) accentCyan else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentlySelected) Color(0xFF1E293B) else Color(0xFF0F172A).copy(alpha = 0.6f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = model.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrentlySelected) accentCyan else Color.White
                                    )

                                    if (isCurrentlySelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Active Model",
                                            tint = accentCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Text(
                                    text = model.description,
                                    fontSize = 11.sp,
                                    color = Color.LightGray.copy(alpha = 0.8f)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Badges
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Memory badge
                                    Badge(
                                        containerColor = Color.DarkGray,
                                        contentColor = Color.White
                                    ) {
                                        Text("${model.minDeviceMemoryInGb}GB RAM MIN", fontSize = 8.sp, modifier = Modifier.padding(2.dp))
                                    }

                                    // Execution platform
                                    Badge(
                                        containerColor = if (model.runtimeType == "aicore") Color(0xFF3B82F6) else Color(0xFFF59E0B),
                                        contentColor = Color.White
                                    ) {
                                        Text(model.runtimeType.uppercase(), fontSize = 8.sp, modifier = Modifier.padding(2.dp))
                                    }

                                    if (model.llmSupportThinking) {
                                        Badge(
                                            containerColor = Color(0xFF8B5CF6), // Purple
                                            contentColor = Color.White
                                        ) {
                                            Text("THINKING", fontSize = 8.sp, modifier = Modifier.padding(2.dp))
                                        }
                                    }

                                    if (model.llmSupportImage) {
                                        Badge(
                                            containerColor = Color(0xFFEC4899), // Pink
                                            contentColor = Color.White
                                        ) {
                                            Text("VISION", fontSize = 8.sp, modifier = Modifier.padding(2.dp))
                                        }
                                    }
                                }

                                if (model.url.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.25f), shape = RoundedCornerShape(6.dp))
                                            .border(1.dp, Color.LightGray.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(model.url))
                                                Toast.makeText(context, "Copied download link!", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🔗 LINK:",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = accentCyan,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                        Text(
                                            text = model.url,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = accentCyan,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "COPY",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.LightGray.copy(alpha = 0.8f),
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Realtime Logs Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GATEWAY INWARD TRAFFIC LOGS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentCyan,
                        letterSpacing = 1.sp
                    )

                    TextButton(
                        onClick = { viewModel.clearLogHistory() },
                        enabled = logs.isNotEmpty()
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear logs", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("CLEAR", fontSize = 11.sp)
                    }
                }
            }

            if (logs.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Terminal placeholder",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No inward queries logged yet.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Set up your client to point to the base URL above and issue OpenAI chat API calls to test.",
                            color = Color.Gray.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(logs) { log ->
                    LogItemRow(log = log, surfaceColor = surfaceCardColor)
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: GatewayLog, surfaceColor: Color) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss.S", Locale.getDefault()) }
    val formattedTime = formatter.format(Date(log.timestamp))

    val statusColor = when {
        log.status == 200 -> Color(0xFF10B981) // Green
        log.status == 401 -> Color(0xFFF59E0B) // Amber
        else -> Color(0xFFEF4444) // Red
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.method,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.DarkGray, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.path,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.LightGray
                    )
                }

                Text(
                    text = "${log.status}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Client: ${log.clientIp} • Model: ${log.requestModel}",
                    fontSize = 10.sp,
                    color = Color.LightGray.copy(alpha = 0.7f)
                )

                Text(
                    text = "${log.durationMs} ms",
                    fontSize = 10.sp,
                    color = Color.LightGray.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }

            if (log.responsePreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = log.responsePreview,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.LightGray,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Time: $formattedTime",
                fontSize = 8.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
