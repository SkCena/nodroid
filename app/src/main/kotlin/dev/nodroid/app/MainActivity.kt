package dev.nodroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nodroid.canvas.CanvasScreen
import dev.nodroid.canvas.CanvasViewModel
import dev.nodroid.canvas.NodRoidTheme
import dev.nodroid.canvas.StarterTemplates
import dev.nodroid.database.AppDatabase
import dev.nodroid.database.RunLogEntity
import dev.nodroid.database.WorkflowEntity
import dev.nodroid.model.GraphJson
import dev.nodroid.model.WorkflowGraph
import dev.nodroid.runtime.RuntimeHolder
import dev.nodroid.security.ExportScrubber
import dev.nodroid.security.VaultManager
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RUN_TILE = "run_tile"
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_SHARED_URI = "shared_uri"
    }

    private val database: AppDatabase by inject()
    private val vault: VaultManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT)
        val sharedUri = intent.getStringExtra(EXTRA_SHARED_URI)
        val runTile = intent.getBooleanExtra(EXTRA_RUN_TILE, false)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                background = Color.Black, surface = Color(0xFF0B0F17), primary = Color(0xFF22D3EE),
            )) {
                NodRoidRoot(database, vault, sharedText, sharedUri, runTile)
            }
        }
    }
}

private enum class Tab { DASHBOARD, CANVAS, HISTORY, VAULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodRoidRoot(
    database: AppDatabase,
    vault: VaultManager,
    sharedText: String?,
    sharedUri: String?,
    runTile: Boolean,
) {
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var workflows by remember { mutableStateOf<List<WorkflowEntity>>(emptyList()) }
    var selectedGraph by remember { mutableStateOf<WorkflowGraph?>(null) }
    var statusMessage by remember { mutableStateOf(if (sharedText != null) "Shared: " + sharedText.take(80) else "") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch { workflows = database.workflowDao().getAll() }
    }
    LaunchedEffect(Unit) {
        refresh()
        if (runTile) {
            val first = database.workflowDao().getAll().firstOrNull()
            if (first != null) {
                statusMessage = "Tile run started: " + first.name
                RuntimeHolder.runWorkflowAsync(first.id, "tile-" + UUID.randomUUID())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "NodRoid", fontFamily = FontFamily.Monospace) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B0F17)) {
                for (entry in listOf(Tab.DASHBOARD to "Dashboard", Tab.CANVAS to "Canvas", Tab.HISTORY to "History", Tab.VAULT to "Vault")) {
                    NavigationBarItem(
                        selected = tab == entry.first,
                        onClick = { tab = entry.first },
                        icon = {},
                        label = { Text(entry.second) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardScreen(
                    workflows = workflows,
                    statusMessage = statusMessage,
                    onImportTemplate = { template ->
                        scope.launch {
                            database.workflowDao().upsert(WorkflowEntity(template.id, template.name, GraphJson.encode(template)))
                            refresh()
                            statusMessage = "Imported: " + template.name
                        }
                    },
                    onOpen = { entity ->
                        scope.launch {
                            selectedGraph = GraphJson.decode(entity.graphJson)
                            tab = Tab.CANVAS
                        }
                    },
                    onRun = { entity ->
                        scope.launch {
                            statusMessage = "Running: " + entity.name
                            val ok = RuntimeHolder.runWorkflow(entity.id, "manual-" + UUID.randomUUID(),
                                mapOf("text" to (sharedText ?: ""), "imageUri" to (sharedUri ?: "")))
                            statusMessage = if (ok) "Finished: " + entity.name else "Failed: " + entity.name
                        }
                    },
                    onExport = { entity ->
                        scope.launch {
                            val scrubbed = ExportScrubber.scrubJson(entity.graphJson)
                            statusMessage = if (ExportScrubber.containsVaultReference(entity.graphJson)) {
                                "Exported (secrets removed), " + scrubbed.length + " chars"
                            } else {
                                "Exported, " + scrubbed.length + " chars"
                            }
                        }
                    },
                )
                Tab.CANVAS -> {
                    val graph = selectedGraph ?: StarterTemplates.priceChecker
                    val viewModel = remember(graph.id) { CanvasViewModel(graph) }
                    val canvasState by viewModel.state.collectAsState()
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Button(onClick = { viewModel.undo() }, enabled = canvasState.canUndo) { Text("Undo") }
                            Button(onClick = { viewModel.redo() }, enabled = canvasState.canRedo, modifier = Modifier.padding(start = 8.dp)) { Text("Redo") }
                            Button(onClick = { viewModel.autoLayout() }, modifier = Modifier.padding(start = 8.dp)) { Text("Auto-layout") }
                            Button(onClick = {
                                scope.launch {
                                    val g = viewModel.state.value.graph
                                    database.workflowDao().upsert(WorkflowEntity(g.id, g.name, GraphJson.encode(g)))
                                    val ok = RuntimeHolder.runWorkflow(g.id, "test-" + UUID.randomUUID())
                                    statusMessage = if (ok) "Test run passed" else "Test run failed"
                                }
                            }, modifier = Modifier.padding(start = 8.dp)) { Text("Test Run") }
                        }
                        if (statusMessage.isNotEmpty()) Text(text = statusMessage, color = NodRoidTheme.TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
                        CanvasScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }
                Tab.HISTORY -> HistoryScreen(database = database)
                Tab.VAULT -> VaultScreen(vault = vault)
            }
        }
    }
}

@Composable
fun DashboardScreen(
    workflows: List<WorkflowEntity>,
    statusMessage: String,
    onImportTemplate: (WorkflowGraph) -> Unit,
    onOpen: (WorkflowEntity) -> Unit,
    onRun: (WorkflowEntity) -> Unit,
    onExport: (WorkflowEntity) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(text = "NodRoid — Visual Node Workflow Studio", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            Text(text = "Offline-first. Your workflows, your device.", color = NodRoidTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (statusMessage.isNotEmpty()) Text(text = statusMessage, color = Color(0xFF22D3EE), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            Text(text = "STARTER TEMPLATES", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        }
        items(StarterTemplates.ALL) { template ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onImportTemplate(template) }) {
                Text(text = template.name, color = Color.White, modifier = Modifier.weight(1f))
                Text(text = "IMPORT", color = Color(0xFF22D3EE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        item { Text(text = "YOUR WORKFLOWS (" + workflows.size + ")", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)) }
        items(workflows) { entity ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(text = entity.name, color = Color.White)
                Row {
                    Text(text = "OPEN", color = Color(0xFF22D3EE), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.clickable { onOpen(entity) }.padding(end = 12.dp))
                    Text(text = "RUN", color = Color(0xFF10B981), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.clickable { onRun(entity) }.padding(end = 12.dp))
                    Text(text = "EXPORT (SAFE)", color = Color(0xFFF59E0B), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.clickable { onExport(entity) })
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(database: AppDatabase) {
    var logs by remember { mutableStateOf<List<RunLogEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch { logs = database.runLogDao().latest(100) } }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text(text = "RUN HISTORY (" + logs.size + ")", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        items(logs) { log ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(text = log.nodeId + " — " + log.status + " (" + log.durationMs + " ms)", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (log.message.isNotEmpty()) Text(text = log.message, color = NodRoidTheme.TextMuted, fontSize = 11.sp)
            }
        }
        if (logs.isEmpty()) item { Text(text = "No runs yet. Run a workflow from the Dashboard.", color = NodRoidTheme.TextMuted, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
fun VaultScreen(vault: VaultManager) {
    var keys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun refresh() { scope.launch { keys = vault.listKeys() } }
    LaunchedEffect(Unit) { refresh() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(text = "VAULT — Tink AES-256-GCM + Android Keystore", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(text = "Keys are referenced as {{vault.KEY}} and never leave the device in exports.", color = NodRoidTheme.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            TextField(value = newKey, onValueChange = { newKey = it }, label = { Text("Key name") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            TextField(value = newValue, onValueChange = { newValue = it }, label = { Text("Secret value") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = {
                scope.launch {
                    if (newKey.isNotBlank() && newValue.isNotBlank()) {
                        vault.put(newKey.trim(), newValue)
                        message = "Saved: " + newKey.trim()
                        newKey = ""; newValue = ""
                        refresh()
                    }
                }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Save to vault") }
            if (message.isNotEmpty()) Text(text = message, color = Color(0xFF22D3EE), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text(text = "STORED KEYS (" + keys.size + ")", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
        }
        items(keys.toList()) { key ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(text = key, color = Color.White, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(text = "DELETE", color = Color(0xFFFF6B5E), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.clickable {
                    scope.launch { vault.delete(key); refresh() }
                })
            }
        }
    }
}
