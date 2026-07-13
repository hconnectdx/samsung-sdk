package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * POST {baseUrl}/poli/sleep/stop
 *
 * Body (JSON):
 *  - reqDate   (yyyyMMddHHmmss, 14자)
 *  - userSno
 *  - sessionId (yyyyMMdd_HHmmss, 15자)
 *
 * protocol8-1 전송 완료 후 호출하여 서버에 수면 세션 종료를 알린다.
 */
internal object SleepStopAPI {

    private const val TAG = "SleepStopAPI"
    private const val ENDPOINT = "poli/sleep/stop-1"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    data class Response(
        val httpCode: Int,
        val body: String,
        val success: Boolean,
        /** 수면 품질 점수. 서버 응답 JSON 의 `sleepQuality` 필드. 파싱 실패 시 null. */
        val sleepQuality: Int? = null,
    )

    /**
     * 수면 측정 종료 알림 전송 (블로킹 호출).
     *
     * @param reqDate   수집일시 (yyyyMMddHHmmss) — null 이면 현재 시간으로 자동 채움
     * @param userSno   사용자 번호 — null 이면 HealthOnClient.userSno 사용
     * @param sessionId 세션 ID (yyyyMMdd_HHmmss, 15자)
     */
    @Throws(Exception::class)
    fun requestPost(
        reqDate: String? = null,
        userSno: Int? = null,
        sessionId: String,
    ): Response {
        HealthOnClient.checkInitialized()

        val finalReqDate = reqDate ?: DateUtil.getCurrentDateTime()
        val finalUserSno = userSno ?: HealthOnClient.userSno

        require(finalReqDate.length == 14) {
            "reqDate 는 14자리(yyyyMMddHHmmss) 여야 합니다. value=$finalReqDate"
        }
        require(sessionId.length == 15) {
            "sessionId 는 15자리(yyyyMMdd_HHmmss) 여야 합니다. value=$sessionId"
        }

        val json = """{"reqDate":"$finalReqDate","userSno":$finalUserSno,"sessionId":"$sessionId"}"""

        val request = Request.Builder()
            .url(HealthOnClient.absoluteUrl(ENDPOINT))
            .addHeader("accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .addHeader("ClientId", HealthOnClient.clientId)
            .addHeader("ClientSecret", HealthOnClient.clientSecret)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        Log.d(
            TAG,
            "▶ [Request] POST ${request.url}  reqDate=$finalReqDate userSno=$finalUserSno sessionId=$sessionId"
        )

        val startMs = System.currentTimeMillis()

        HealthOnClient.client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startMs
            val resBody = response.body?.string().orEmpty()

            val sleepQuality: Int? = if (response.isSuccessful) {
                try {
                    JSONObject(resBody).let { json ->
                        if (json.isNull("sleepQuality")) null
                        else json.getInt("sleepQuality")
                    }
                } catch (_: JSONException) {
                    null
                }
            } else null

            val logMsg = "◀ [Response] ${response.code} ${response.message} (${elapsedMs}ms)" +
                    " sleepQuality=$sleepQuality body=$resBody"
            if (response.isSuccessful) Log.d(TAG, logMsg) else Log.e(TAG, logMsg)

            return Response(
                httpCode = response.code,
                body = resBody,
                success = response.isSuccessful,
                sleepQuality = sleepQuality,
            )
        }
    }
}
