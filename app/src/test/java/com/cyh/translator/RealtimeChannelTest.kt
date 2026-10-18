package com.cyh.translator

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RealtimeChannelTest {
    @Test fun secureSocketWaitsForConfigurationAndStreamsPcmAndCaptions() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        val messages = LinkedBlockingQueue<JSONObject>()
        val opened = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.offer(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.offer(JSONObject(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        server.start()
        val ready = CountDownLatch(1)
        val completed = LinkedBlockingQueue<Caption>()
        val errors = LinkedBlockingQueue<String>()
        val settings = AppSettings(endpoint = server.url("/realtime?region=cn").toString().replaceFirst("https", "wss"))
        val channel = RealtimeChannel(settings, settings.speak, "test-secret", {}, { ready.countDown() },
            { if (it.status == "translated") completed.offer(it) }, { _, _ -> }, { errors.offer(it) }, client)
        try {
            channel.start()
            val socket = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
            assertEquals("session.update", messages.poll(5, TimeUnit.SECONDS)?.optString("type"))
            channel.audio(ByteArray(3200) { 1 })
            assertNull("Audio must wait for session.updated", messages.poll(200, TimeUnit.MILLISECONDS))
            socket.send("{\"type\":\"session.updated\",\"session\":{\"output_modalities\":[\"text\"]}}")
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            channel.audio(ByteArray(3200) { 1 })
            val audio = requireNotNull(messages.poll(5, TimeUnit.SECONDS))
            assertEquals("input_audio_buffer.append", audio.getString("type"))
            assertArrayEquals(ByteArray(3200) { 1 }, java.util.Base64.getDecoder().decode(audio.getString("audio")))
            channel.muted = true; channel.audio(ByteArray(3200) { 2 })
            assertNull("Muted audio must not leave the device", messages.poll(200, TimeUnit.MILLISECONDS))
            socket.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"s1\",\"transcript\":\"你好\"}")
            socket.send("{\"type\":\"response.text.done\",\"response_id\":\"r1\",\"text\":\"Hello\"}")
            val caption = requireNotNull(completed.poll(5, TimeUnit.SECONDS))
            assertEquals("你好", caption.source); assertEquals("Hello", caption.translation)
            // Text-only app: server audio events must never surface as captions or errors.
            socket.send("{\"type\":\"response.audio.delta\",\"delta\":\"QUJDREVG\"}")
            assertNull("Server audio must be ignored", completed.poll(300, TimeUnit.MILLISECONDS))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            assertTrue(request.path!!.contains("region=cn&model=")); assertTrue(errors.isEmpty())
            channel.stop()
            assertEquals("session.finish", messages.poll(5, TimeUnit.SECONDS)?.optString("type"))
        } finally { channel.stop(); server.close() }
    }
}
