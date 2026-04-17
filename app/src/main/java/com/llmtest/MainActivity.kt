package com.llmtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var engine: Engine? = null
    private var selectedEntity by mutableStateOf<Pair<String, String>?>(null)

    companion object {
        private val entityList = mutableStateListOf<Pair<String, String>>()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BugLogger.initialize(this)
        BugLogger.log("MainActivity.onCreate() started")
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SanctionsScreen()
                }
            }
        }
    }
    
    private fun hasStoragePermission(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        BugLogger.log("hasStoragePermission() = $result")
        return result
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
    
    private suspend fun loadEntityList() = withContext(Dispatchers.IO) {
        BugLogger.log("Loading entity list from ${GhostPaths.ENTITIES_JSON}")
        if (!GhostPaths.isEntitiesJsonAvailable()) {
            BugLogger.log("entities.ftm.json not found at ${GhostPaths.ENTITIES_JSON}")
            return@withContext
        }
        
        entityList.clear()
        
        try {
            BufferedReader(FileReader(GhostPaths.ENTITIES_JSON)).use { reader ->
                var count = 0
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank() && count < 500) { // Limit to 500 for performance
                        try {
                            val json = org.json.JSONObject(line)
                            val id = json.optString("id", "")
                            val caption = json.optString("caption", "")
                            val schema = json.optString("schema", "")
                            if (id.isNotEmpty() && caption.isNotEmpty() && 
                                (schema == "Person" || schema == "Company")) {
                                entityList.add(id to caption)
                                count++
                            }
                        } catch (e: Exception) {
                            // Skip invalid lines
                        }
                    }
                }
                BugLogger.log("Loaded $count entities")
            }
        } catch (e: Exception) {
            BugLogger.logError("Failed to load entities", e)
        }
    }
    
    private suspend fun getEntityDetails(entityId: String): String = withContext(Dispatchers.IO) {
        BugLogger.log("Getting details for entity: $entityId")
        if (!GhostPaths.isEntitiesJsonAvailable()) {
            return@withContext "No entity data available"
        }
        
        try {
            BufferedReader(FileReader(GhostPaths.ENTITIES_JSON)).use { reader ->
                reader.lineSequence().forEach { line ->
                    try {
                        val json = JSONObject(line)
                        if (json.optString("id") == entityId) {
                            BugLogger.log("Found entity data")
                            return@withContext json.toString(2) // Pretty print
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            BugLogger.logError("Error reading entity", e)
        }
        return@withContext "Entity data not found"
    }
    
    private suspend fun querySanctionsForEntity(entityId: String): List<String> = withContext(Dispatchers.IO) {
        BugLogger.log("Querying sanctions_entities for: $entityId")
        val matches = mutableListOf<String>()

        if (!GhostPaths.isSanctionsDbAvailable()) {
            BugLogger.log("Sanctions DB not available")
            return@withContext matches
        }

        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            GhostPaths.SANCTIONS_DB.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )

        try {
            // Query the REGULAR table (not FTS5 virtual table)
            BugLogger.log("Querying sanctions_entities table...")
            val cursor = db.rawQuery(
                "SELECT entity_id, canonical_name, schema_type, countries, programs, topics " +
                "FROM sanctions_entities WHERE entity_id = ? LIMIT 1",
                arrayOf(entityId)
            )

            if (cursor.moveToFirst()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: "Unknown"
                val schema = cursor.getString(2) ?: "Unknown"
                val countries = cursor.getString(3) ?: "N/A"
                val programs = cursor.getString(4) ?: "None"
                val topics = cursor.getString(5) ?: ""

                val isSanctioned = topics.contains("sanction") || programs.isNotBlank()
                val status = if (isSanctioned) "🚨 SANCTIONED" else "Not sanctioned"

                matches.add("Name: $name")
                matches.add("Type: $schema")
                matches.add("Status: $status")
                matches.add("Programs: $programs")
                matches.add("Countries: $countries")
                if (topics.isNotBlank()) matches.add("Topics: $topics")

                BugLogger.log("Found: $name ($status)")
            } else {
                BugLogger.log("No match found in sanctions_entities")
            }
            cursor.close()

            // Also try partial match on canonical_name if no exact ID match
            if (matches.isEmpty() && selectedEntity != null) {
                val searchName = selectedEntity!!.second.take(30) // First 30 chars
                BugLogger.log("Trying partial name match: $searchName")

                val nameCursor = db.rawQuery(
                    "SELECT entity_id, canonical_name, schema_type, programs, topics " +
                    "FROM sanctions_entities WHERE canonical_name LIKE ? LIMIT 5",
                    arrayOf("%$searchName%")
                )

                while (nameCursor.moveToNext()) {
                    val id = nameCursor.getString(0)
                    val name = nameCursor.getString(1)
                    val schema = nameCursor.getString(2)
                    val programs = nameCursor.getString(3)
                    val topics = nameCursor.getString(4)
                    val isSanctioned = topics.contains("sanction") || programs.isNotBlank()
                    val status = if (isSanctioned) "🚨 SANCTIONED" else "OK"

                    matches.add("Similar: $name ($schema) - $status")
                }
                nameCursor.close()
            }

        } catch (e: Exception) {
            BugLogger.logError("Database query failed", e)
        } finally {
            db.close()
        }

        BugLogger.log("Total matches: ${matches.size}")
        matches
    }
    
    private suspend fun analyzeWithLLM(entityId: String, entityData: String, sanctionsMatches: List<String>): String = withContext(Dispatchers.IO) {
        BugLogger.log("Analyzing with LLM for: $entityId")
        
        if (engine == null) {
            BugLogger.log("Initializing engine first...")
            val modelFile = GhostPaths.MODEL_FILE
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                maxNumTokens = 2048,
                cacheDir = cacheDir.path
            )
            engine = Engine(config)
            engine?.initialize()
            BugLogger.log("Engine initialized")
        }
        
        val currentEngine = engine ?: throw IllegalStateException("Engine not available")
        
        val prompt = buildString {
            appendLine("You are a sanctions screening analyst. Analyze ONLY the data provided below.")
            appendLine("Do not use any external knowledge. Base your entire analysis strictly on the provided local data.")
            appendLine()
            appendLine("=== ENTITY DATA FROM LOCAL DATABASE ===")
            appendLine(entityData)
            appendLine()
            appendLine("=== SANCTIONS DATABASE MATCHES ===")
            if (sanctionsMatches.isEmpty()) {
                appendLine("No direct matches found in sanctions database.")
            } else {
                sanctionsMatches.forEachIndexed { index, match ->
                    appendLine("${index + 1}. $match")
                }
            }
            appendLine()
            appendLine("=== ANALYSIS INSTRUCTIONS ===")
            appendLine("1. Identify the entity type (Person or Company)")
            appendLine("2. List any sanctions programs or restrictions mentioned in the data")
            appendLine("3. Note any aliases or related entities")
            appendLine("4. Provide a factual risk assessment based ONLY on the data above")
            appendLine("5. State clearly if no sanctions were found")
            appendLine()
            appendLine("Provide a concise, factual summary in 3-5 sentences.")
        }
        
        BugLogger.log("Prompt length: ${prompt.length}")
        
        try {
            val conversation = currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        temperature = 0.5,  // FACTUAL - as requested
                        topK = 40,
                        topP = 0.9
                    )
                )
            )
            
            conversation.use {
                BugLogger.log("Sending to LLM...")
                val response = it.sendMessage(Message.of(prompt))
                val text = response.toString()
                BugLogger.log("Response received: ${text.length} chars")
                text
            }
        } catch (e: Exception) {
            BugLogger.logError("LLM failed", e)
            "Error analyzing data: ${e.message}"
        }
    }
    
    suspend fun analyzeSECFilingChunk(chunkFile: File, chunkNumber: Int, totalChunks: Int): String = withContext(Dispatchers.IO) {
        val logPrefix = "[SEC-CHUNK-$chunkNumber/$totalChunks]"
        BugLogger.log("$logPrefix Starting analysis")
        
        val chunkText = try {
            chunkFile.readText().take(6000)
        } catch (e: Exception) {
            BugLogger.logError("$logPrefix Failed to read chunk", e)
            return@withContext "CHUNK $chunkNumber: READ_FAIL | Error: ${e.message}"
        }
        
        val charCount = chunkText.length
        val estimatedTokens = charCount / 4
        BugLogger.log("$logPrefix Size: $charCount chars ≈ $estimatedTokens tokens")
        
        val prompt = buildString {
            appendLine("You are a financial analyst. Extract key information from this SEC filing excerpt.")
            appendLine()
            appendLine("Chunk $chunkNumber of $totalChunks from Apple Inc. 10-K filing.")
            appendLine()
            appendLine("---FILING EXCERPT---")
            appendLine(chunkText)
            appendLine("---END EXCERPT---")
            appendLine()
            appendLine("Extract ONLY:")
            appendLine("1. Any mentioned subsidiary companies or entities")
            appendLine("2. Any mentioned executive officers or directors")
            appendLine("3. Any mentioned countries or jurisdictions")
            appendLine("4. 'NONE' if nothing relevant found")
            appendLine()
            appendLine("Be concise. List format.")
        }
        
        BugLogger.log("$logPrefix Prompt: ${prompt.length} chars")
        
        if (engine == null) {
            BugLogger.log("$logPrefix Initializing engine...")
            try {
                val modelFile = GhostPaths.MODEL_FILE
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    maxNumTokens = 2500,
                    cacheDir = cacheDir.path
                )
                engine = Engine(config)
                engine?.initialize()
            } catch (e: Exception) {
                return@withContext "CHUNK $chunkNumber: ENGINE_FAIL | ${e.message}"
            }
        }
        
        val startTime = System.currentTimeMillis()
        return@withContext try {
            val conversation = engine!!.createConversation()
            conversation.use {
                val response = it.sendMessage(Message.of(prompt))
                val responseText = response.toString()
                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                
                BugLogger.log("$logPrefix SUCCESS: ${duration}s, response: ${responseText.length}")
                "CHUNK $chunkNumber: SUCCESS | Time: ${duration}s | Tokens: $estimatedTokens | Response: ${responseText.take(80)}..."
            }
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            BugLogger.logError("$logPrefix FAILED", e)
            "CHUNK $chunkNumber: FAILED | Time: ${duration}s | Error: ${e.javaClass.simpleName}"
        }
    }

    suspend fun testSECFilingAnalysis(): String = withContext(Dispatchers.IO) {
        BugLogger.log("[SEC-TEST] Starting SEC filing analysis test")
        
        val cacheDir = File("/storage/emulated/0/Download/GhostModels/ACIS_cache")
        val chunkFiles = cacheDir.listFiles { file -> 
            file.name.startsWith("chunk_") 
        }?.toList()?.sortedBy { it.name } ?: emptyList()
        
        BugLogger.log("[SEC-TEST] Found ${chunkFiles.size} chunks")
        
        if (chunkFiles.isEmpty()) {
            return@withContext "No chunks found. Create chunks first:\n\n1. cd /storage/emulated/0/Download/GhostModels/ACIS_cache/\n2. split -b 6000 AAPL_10K_2023.txt chunk_"
        }
        
        val results = mutableListOf<String>()
        val testChunks = chunkFiles.take(3)
        
        for (index in testChunks.indices) {
            val chunkFile = testChunks[index]
            val result = analyzeSECFilingChunk(chunkFile, index + 1, testChunks.size)
            results.add(result)
            
            if (index < testChunks.size - 1) {
                BugLogger.log("[SEC-TEST] Cooling down 10s...")
                delay(10000)
            }
        }
        
        BugLogger.log("[SEC-TEST] Complete")
        results.joinToString("\n\n")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SanctionsScreen() {
        var status by remember { mutableStateOf("Select entity to screen") }
        var isAnalyzing by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        var hasPermission by remember { mutableStateOf(hasStoragePermission()) }
        var isLoadingEntities by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        
        // Load entities when permission is granted
        LaunchedEffect(hasPermission) {
            if (hasPermission && entityList.isEmpty()) {
                isLoadingEntities = true
                BugLogger.log("Permission granted, loading entities...")
                withContext(Dispatchers.IO) {
                    loadEntityList()
                }
                isLoadingEntities = false
                BugLogger.log("Entities loaded: ${entityList.size}")
            }
        }
        
        // Check permission on resume (when returning from settings)
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val newPermission = hasStoragePermission()
                    BugLogger.log("ON_RESUME - permission: $newPermission")
                    if (newPermission != hasPermission) {
                        hasPermission = newPermission
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Sanctions Screening",
                style = MaterialTheme.typography.headlineMedium
            )
            
            // Status indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        color = when {
                            isAnalyzing -> Color.Yellow
                            result.contains("Error") -> Color.Red
                            result.isNotEmpty() -> Color.Green
                            else -> Color.Gray
                        },
                        shape = CircleShape
                    )
            )
            
            Text(status, style = MaterialTheme.typography.bodyMedium)
            
            if (!hasPermission) {
                Text(
                    "This app needs 'All files access' permission to read the model and sanctions data.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { 
                    BugLogger.log("Opening permission settings...")
                    requestStoragePermission() 
                }) {
                    Text("Grant Storage Permission")
                }
                Text(
                    "Tap button → Toggle permission → Return to app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isLoadingEntities) {
                CircularProgressIndicator()
                Text("Loading entity database...")
            } else if (entityList.isEmpty()) {
                Text("No entities found in database")
                Button(onClick = { 
                    scope.launch {
                        isLoadingEntities = true
                        loadEntityList()
                        isLoadingEntities = false
                    }
                }) {
                    Text("Retry Loading")
                }
            } else {
                // Entity Dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedEntity?.second ?: "Select entity...",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Entity (${entityList.size} available)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        entityList.forEach { (id, caption) ->
                            DropdownMenuItem(
                                text = { Text(caption, maxLines = 1) },
                                onClick = {
                                    selectedEntity = id to caption
                                    expanded = false
                                    result = ""
                                    BugLogger.log("Selected: $caption")
                                }
                            )
                        }
                    }
                }
                
                // Selected entity info
                selectedEntity?.let { (id, caption) ->
                    Text(
                        "ID: $id",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Analyze Button
                Button(
                    onClick = {
                        val entity = selectedEntity
                        if (entity != null) {
                            scope.launch {
                                isAnalyzing = true
                                status = "Loading entity data..."
                                result = ""
                                
                                try {
                                    val entityData = getEntityDetails(entity.first)
                                    status = "Querying sanctions database..."
                                    val sanctions = querySanctionsForEntity(entity.first)
                                    status = "Analyzing with LLM..."
                                    val analysis = analyzeWithLLM(entity.first, entityData, sanctions)
                                    
                                    result = analysis
                                    status = "Analysis complete"
                                } catch (e: Exception) {
                                    BugLogger.logError("Analysis failed", e)
                                    result = "Error: ${e.message}"
                                    status = "Analysis failed"
                                }
                                isAnalyzing = false
                            }
                        }
                    },
                    enabled = selectedEntity != null && !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Screen Entity")
                    }
                }
                
                // Results
                if (result.isNotEmpty()) {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            Text(
                                "Analysis Results",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                result,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                
                // Context Test Button
                Button(
                    onClick = {
                        scope.launch {
                            // Run sequential tests
                            val testLevels = listOf(2000, 4000, 8000, 16000, 24000, 32000, 48000)
                            val testEntity = selectedEntity?.first ?: "NK-223yQP6hRaMuiALDCJ6xbY"
                            
                            result = "Starting context tests...\n"
                            
                            testLevels.forEach { targetTokens ->
                                status = "Testing $targetTokens tokens..."
                                val testResult = testContextLimit(targetTokens, testEntity)
                                result += "\n$testResult\n"
                                // 30 second cooldown between tests
                                if (targetTokens != testLevels.last()) {
                                    delay(30000)
                                }
                            }
                            
                            status = "All tests complete"
                        }
                    },
                    enabled = !isAnalyzing && selectedEntity != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Context Limits")
                }

                // SEC Filing Test Button
                Button(
                    onClick = {
                        scope.launch {
                            isAnalyzing = true
                            result = "Analyzing SEC filing chunks..."
                            
                            val cacheDir = File("/storage/emulated/0/Download/GhostModels/ACIS_cache")
                            val chunksExist = cacheDir.listFiles { f -> 
                                f.name.startsWith("chunk_") 
                            }?.isNotEmpty() == true
                            
                            result = if (chunksExist) {
                                testSECFilingAnalysis()
                            } else {
                                "No chunks found. Run in Termux:\ncd /storage/emulated/0/Download/GhostModels/ACIS_cache/\nsplit -b 6000 AAPL_10K_2023.txt chunk_"
                            }
                            
                            isAnalyzing = false
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test SEC Filing (3 chunks)")
                }
                
                // Debug buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { result = BugLogger.readLog() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("View Logs")
                    }
                    
                    Button(
                        onClick = {
                            val logs = BugLogger.readLog()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ACIS Logs", logs)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy Logs")
                    }
                    
                    Button(
                        onClick = {
                            selectedEntity = null
                            result = ""
                            status = "Select entity to screen"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
    }
    
    suspend fun testContextLimit(targetTokenCount: Int, entityId: String): String = withContext(Dispatchers.IO) {
        val logPrefix = "[TEST-$targetTokenCount]"
        BugLogger.log("$logPrefix Starting context window test")
        
        // 1. Get entity data
        BugLogger.log("$logPrefix Loading entity: $entityId")
        val entityJson = getEntityDetails(entityId)
        val baseChars = entityJson.length
        BugLogger.log("$logPrefix Base entity: $baseChars chars")
        
        // 2. Calculate padding needed (1 token ≈ 3.5 chars)
        val targetChars = (targetTokenCount * 3.5).toInt()
        val paddingNeeded = targetChars - baseChars
        
        BugLogger.log("$logPrefix Target: $targetTokenCount tokens ≈ $targetChars chars")
        BugLogger.log("$logPrefix Padding needed: $paddingNeeded chars")
        
        if (paddingNeeded <= 0) {
            BugLogger.log("$logPrefix Entity already large enough, no padding")
        }
        
        // 3. Build padded content
        val paddingText = if (paddingNeeded > 0) {
            val filler = "This is padding text to increase token count. The secret code is NIGHT-OWL-734. "
            val repeats = (paddingNeeded / filler.length) + 1
            filler.repeat(repeats).take(paddingNeeded)
        } else ""
        
        // Place needle at 75% position if padding exists
        val combinedContent = if (paddingNeeded > 0) {
            val firstPart = paddingText.take((paddingText.length * 0.75).toInt())
            val secondPart = paddingText.drop((paddingText.length * 0.75).toInt())
            "$firstPart[ENTITY_START]$entityJson[ENTITY_END]$secondPart"
        } else {
            entityJson
        }
        
        val totalChars = combinedContent.length
        val estimatedTokens = (totalChars / 3.5).toInt()
        BugLogger.log("$logPrefix Total content: $totalChars chars ≈ $estimatedTokens tokens")
        
        // 4. Build prompt with needle test
        val prompt = buildString {
            appendLine("You are a sanctions screening analyst.")
            appendLine("Analyze the following entity data and provide a brief summary.")
            appendLine("If you find the text 'NIGHT-OWL-734' anywhere in the data, include it in your response.")
            appendLine()
            appendLine("---BEGIN DATA---")
            appendLine(combinedContent)
            appendLine("---END DATA---")
        }
        
        BugLogger.log("$logPrefix Prompt built: ${prompt.length} chars")
        
        // 5. Initialize engine if needed
        if (engine == null) {
            BugLogger.log("$logPrefix Initializing engine...")
            val modelFile = GhostPaths.MODEL_FILE
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                maxNumTokens = targetTokenCount + 500, // Allow some headroom
                cacheDir = cacheDir.path
            )
            engine = Engine(config)
            engine?.initialize()
            BugLogger.log("$logPrefix Engine initialized")
        }
        
        // 6. Execute inference with timing
        val startTime = System.currentTimeMillis()
        BugLogger.log("$logPrefix Starting inference...")
        
        return@withContext try {
            val conversation = engine!!.createConversation()
            conversation.use {
                val response = it.sendMessage(Message.of(prompt))
                val responseText = response.toString()
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime) / 1000.0
                
                // Check if needle was found
                val foundNeedle = responseText.contains("NIGHT-OWL-734")
                
                BugLogger.log("$logPrefix SUCCESS: Response in ${duration}s, length: ${responseText.length}")
                BugLogger.log("$logPrefix Needle found: $foundNeedle")
                
                "TEST $targetTokenCount: SUCCESS | Time: ${duration}s | Tokens est: $estimatedTokens | Response: ${responseText.length} chars | Needle: $foundNeedle"
            }
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime) / 1000.0
            BugLogger.logError("$logPrefix FAILED after ${duration}s", e)
            "TEST $targetTokenCount: FAILED | Time: ${duration}s | Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BugLogger.log("MainActivity.onDestroy()")
        engine?.close()
    }
}
