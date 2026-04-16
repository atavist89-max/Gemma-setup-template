package com.llmtest

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    
    private var engine: Engine? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConnectionTestScreen()
                }
            }
        }
    }
    
    @Composable
    fun ConnectionTestScreen() {
        var status by remember { mutableStateOf(Status.IDLE) }
        var message by remember { mutableStateOf("Press button to test connection") }
        val scope = rememberCoroutineScope()
        
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Light
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        color = when (status) {
                            Status.IDLE -> Color.Gray
                            Status.TESTING -> Color.Yellow
                            Status.CONNECTED -> Color.Green
                            Status.FAILED -> Color.Red
                        },
                        shape = CircleShape
                    )
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    scope.launch {
                        status = Status.TESTING
                        message = "Testing connection..."
                        
                        val (success, errorMsg) = testConnection()
                        
                        status = if (success) Status.CONNECTED else Status.FAILED
                        message = errorMsg
                        
                        showToast(if (success) "Connected!" else errorMsg.take(100))
                    }
                },
                enabled = status != Status.TESTING
            ) {
                Text("Test LLM Connection")
            }
        }
    }
    
    private suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File("/storage/emulated/0/Download/GhostModels/gemma-4-e2b.litertlm")
            
            if (!modelFile.exists()) {
                return@withContext Pair(false, "Model file not found at ${modelFile.absolutePath}")
            }
            
            // Check file size
            val fileSizeMB = modelFile.length() / (1024 * 1024)
            if (fileSizeMB < 1000) {
                return@withContext Pair(false, "Model file too small (${fileSizeMB}MB), may be corrupted")
            }
            
            // Try GPU first
            try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    maxNumTokens = 1024,
                    cacheDir = cacheDir.path
                )
                
                val testEngine = Engine(config)
                testEngine.initialize()
                testEngine.close()
                
                return@withContext Pair(true, "GPU Backend Connected (${fileSizeMB}MB model)")
                
            } catch (gpuError: Exception) {
                // GPU failed, try CPU
                try {
                    val cpuConfig = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        maxNumTokens = 1024,
                        cacheDir = cacheDir.path
                    )
                    
                    val cpuEngine = Engine(cpuConfig)
                    cpuEngine.initialize()
                    cpuEngine.close()
                    
                    return@withContext Pair(true, "CPU Backend Connected (GPU failed: ${gpuError.message})")
                    
                } catch (cpuError: Exception) {
                    return@withContext Pair(false, 
                        "GPU: ${gpuError.javaClass.simpleName}: ${gpuError.message}\n" +
                        "CPU: ${cpuError.javaClass.simpleName}: ${cpuError.message}"
                    )
                }
            }
            
        } catch (e: Exception) {
            return@withContext Pair(false, "Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    
    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
    
    enum class Status { IDLE, TESTING, CONNECTED, FAILED }
    
    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
    }
}
