package com.example.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.GatewayRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyServerManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: GatewayRepository
    private lateinit var serverManager: ProxyServerManager
    private val testPort = 9091

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Setup in-memory SQLite Room Database for fast, clean hermetic data isolation.
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        repository = GatewayRepository(db.proxySettingDao(), db.gatewayLogDao())
        serverManager = ProxyServerManager(context, repository)
    }

    @After
    fun tearDown() = runBlocking {
        serverManager.stopServer()
        db.close()
    }

    @Test
    fun testServerRejectsInvalidJsonWith400() = runBlocking {
        // 1. Start the HTTP Proxy server synchronously on a dynamic unit testing port
        serverManager.startServer(testPort)
        
        // Give the socket server a brief instant to transition its thread state
        delay(500)
        assertTrue("Server should be running in active loop", serverManager.isServerRunning.value)

        // 2. Open a direct TCP Socket to the running server instance
        val socket = Socket("127.0.0.1", testPort)
        socket.use { client ->
            val writer = OutputStreamWriter(client.getOutputStream(), "UTF-8")
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))

            // 3. Draft a raw POST request with deliberate malformed, syntactically broken JSON body: "messag":es
            val invalidJsonBody = """{"model": "litert-community/gemma-4-E4B-it-litert-lm", "messag":es [{"role": "user"}]}"""
            val contentLength = invalidJsonBody.toByteArray(Charsets.UTF_8).size

            val rawHttpRequest = "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$testPort\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    invalidJsonBody

            // 4. Send the packet downstream
            writer.write(rawHttpRequest)
            writer.flush()

            // 5. Read and aggregate response lines
            val headers = mutableListOf<String>()
            var responseLine: String?
            while (reader.readLine().also { responseLine = it } != null) {
                val lineStr = responseLine!!
                if (lineStr.isEmpty()) {
                    break // end of Headers block
                }
                headers.add(lineStr)
            }

            // Assert that the server responded with 400 Bad Request
            assertTrue("Expected HTTP/1.1 400 Bad Request header line", headers.isNotEmpty())
            val statusLine = headers[0]
            assertTrue("Status line must contain 400, got: $statusLine", statusLine.contains("400"))

            // Read the remaining body text containing the JSON error
            val bodyBuilder = java.lang.StringBuilder()
            var charCode: Int
            while (reader.read().also { charCode = it } != -1) {
                bodyBuilder.append(charCode.toChar())
            }
            val bodyText = bodyBuilder.toString()

            // 6. Validate error payload
            val errorResponseJson = JSONObject(bodyText)
            assertTrue("Should contain outer error key", errorResponseJson.has("error"))
            val errorDetails = errorResponseJson.getJSONObject("error")
            
            assertEquals("invalid_request_error", errorDetails.getString("type"))
            assertEquals(400, errorDetails.getInt("code"))
            
            val message = errorDetails.getString("message")
            assertTrue(
                "Descriptive error message should mention JSON syntax issues, got: $message",
                message.contains("JSON") || message.contains("syntax") || message.contains("Value")
            )
        }
    }
}
