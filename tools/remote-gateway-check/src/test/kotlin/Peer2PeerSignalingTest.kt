import com.azurpilot.ghio.remoteaccess.Peer2PeerSignaling
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** 锁定公开信令路径，避免 SDP 请求误发到相邻 API。 / Locks public signaling paths so SDP never hits adjacent APIs. */
class Peer2PeerSignalingTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val entry get() = "https://registry.example/d/$id/"

    @Test fun postsOfferAndPollsAnswerAtExactPaths() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += "${request.method} ${request.url.encodedPath}"
            val body = if (request.method == "POST") JSONObject().put("id", "signal-id")
            else JSONObject().put("answer", "answer-sdp")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toString().toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val signaling = Peer2PeerSignaling(client)

        assertEquals("signal-id", signaling.offer(entry, id, "offer-sdp"))
        assertEquals("answer-sdp", signaling.answer(entry, id, "signal-id"))
        assertEquals(
            listOf(
                "POST /api/v1/devices/$id/signal",
                "GET /api/v1/devices/$id/signal/signal-id",
            ),
            paths,
        )
    }
}
