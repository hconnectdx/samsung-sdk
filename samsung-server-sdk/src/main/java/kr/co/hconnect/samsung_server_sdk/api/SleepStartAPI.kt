package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST {baseUrl}/poli/sleep/start
 *
 * Body (JSON):
 *  - reqDate  (yyyyMMddHHmmss, 14자)
 *  - userSno
 *
 * 수면(SLEEP) 측정 시작 직후 호출하여 서버에 세션 시작을 알린다.
 */
internal object SleepStartAPI {

    private const val TAG = "SleepStartAPI"
    private const val ENDPOINT = "poli/sleep/start"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    data class Response(
        val httpCode: Int,
        val body: String,
        val success: Boolean,
    )

    /**
     * 수면 측정 시작 알림 전송 (블로킹 호출).
     *
     * @param reqDate  수집일시 (yyyyMMddHHmmss) — null 이면 현재 시간으로 자동 채움
     * @param userSno  사용자 번호 — null 이면 HealthOnClient.userSno 사용
     */
    @Throws(Exception::class)
    fun requestPost(
        reqDate: String? = null,
        userSno: Int? = null,
    ): Response {
        HealthOnClient.checkInitialized()

        val finalReqDate = reqDate ?: DateUtil.getCurrentDateTime()
        val finalUserSno = userSno ?: HealthOnClient.userSno

        require(finalReqDate.length == 14) {
            "reqDate 는 14자리(yyyyMMddHHmmss) 여야 합니다. value=$finalReqDate"
        }

        val json = """{"reqDate":"$finalReqDate","userSno":$finalUserSno}"""

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
            "▶ [Request] POST ${request.url}  reqDate=$finalReqDate userSno=$finalUserSno"
        )

        val startMs = System.currentTimeMillis()

        HealthOnClient.client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startMs
            val resBody = response.body?.string().orEmpty()

            val logMsg = "◀ [Response] ${response.code} ${response.message} (${elapsedMs}ms) body=$resBody"
            if (response.isSuccessful) Log.d(TAG, logMsg) else Log.e(TAG, logMsg)

            return Response(
                httpCode = response.code,
                body = resBody,
                success = response.isSuccessful,
            )
        }
    }
}
