package com.example.proxytester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.proxytester.model.ProxyResult
import com.example.proxytester.repository.ProxyRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProxyTesterScreen()
                }
            }
        }
    }
}

@Composable
fun ProxyTesterScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { ProxyRepository(cacheDir = context.cacheDir) }
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProxyResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Telegram Proxy Tester", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Proxy link (tg://, t.me/proxy, socks5://)") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                errorText = null
                result = null
                isTesting = true
                scope.launch {
                    try {
                        val r = repository.testSingle(input)
                        if (r == null) {
                            errorText = "Could not parse this proxy link. Check the format."
                        } else {
                            result = r
                        }
                    } catch (e: Exception) {
                        errorText = e.message ?: "Unknown error"
                    } finally {
                        isTesting = false
                    }
                }
            },
            enabled = input.isNotBlank() && !isTesting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isTesting) "Testing..." else "TEST")
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        result?.let { r ->
            Divider()
            Text("Result", style = MaterialTheme.typography.titleMedium)
            Text("Type: ${r.proxy.type}")
            Text("Server: ${r.proxy.server}")
            Text("Port: ${r.proxy.port}")
            Text("Ping: ${r.pingMs} ms")
            Text("Status: ${if (r.success) "✅ WORKING" else "❌ FAILED (${r.reason})"}")
            Text(r.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
