package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST {baseUrl}/poli/day/protocol2-1
 *
 * Form fields:
 *  - reqDate  (yyyyMMddHHmmss)
 *  - userSno
 *  - userAge
 *  - ppgFile  (CSV: GREEN, IR, RED)
 *  - ecgFile  (CSV: value, lead_off, max_threshold_mv, min_threshold_mv, seq)
 *
 * polihealth-sdk-android-v2 의 DailyProtocol02API.requestPost 와 동일한 패턴.
 * (Ktor 대신 OkHttp 멀티파트 사용)
 */
object Protocol2_1API {

    private const val TAG = "Protocol2_1API"
    private const val ENDPOINT = "poli/day/protocol2-1"

    private val CSV_MEDIA_TYPE = "text/csv".toMediaType()

    data class Response(
        val httpCode: Int,
        val body: String,
        val success: Boolean,
    )

    /**
     * Protocol 2-1 측정 결과 전송 (블로킹 호출).
     *
     * @param reqDate    수집일시 (yyyyMMddHHmmss) — null 이면 현재 시간으로 자동 채움
     * @param userSno    사용자 번호 — null 이면 HealthOnClient.userSno 사용
     * @param userAge    사용자 나이 — null 이면 HealthOnClient.userAge 사용
     * @param ppgCsv     PPG CSV 바이트 (GREEN, IR, RED)
     * @param ecgCsv     ECG CSV 바이트 (value, lead_off, max_threshold_mv, min_threshold_mv, seq)
     * @param ppgFileName 첨부 파일명 (기본 "ppg.csv")
     * @param ecgFileName 첨부 파일명 (기본 "ecg.csv")
     */
    @Throws(Exception::class)
    fun requestPost(
        reqDate: String? = null,
        userSno: Int? = null,
        userAge: Int? = null,
        ppgCsv: ByteArray,
        ecgCsv: ByteArray,
        ppgFileName: String = "ppg.csv",
        ecgFileName: String = "ecg.csv",
    ): Response {
        HealthOnClient.checkInitialized()

        val finalReqDate = reqDate ?: DateUtil.getCurrentDateTime()
        val finalUserSno = userSno ?: HealthOnClient.userSno
        val finalUserAge = userAge ?: HealthOnClient.userAge

        require(finalReqDate.length == 14) {
            "reqDate 는 14자리(yyyyMMddHHmmss) 여야 합니다. value=$finalReqDate"
        }
        require(ppgCsv.isNotEmpty()) { "ppgCsv 가 비어 있습니다." }
        require(ecgCsv.isNotEmpty()) { "ecgCsv 가 비어 있습니다." }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqDate", finalReqDate)
            .addFormDataPart("userSno", finalUserSno.toString())
            .addFormDataPart("userAge", finalUserAge.toString())
            .addFormDataPart(
                "ppgFile",
                ppgFileName,
                ppgCsv.toRequestBody(CSV_MEDIA_TYPE)
            )
            .addFormDataPart(
                "ecgFile",
                ecgFileName,
                ecgCsv.toRequestBody(CSV_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url(HealthOnClient.absoluteUrl(ENDPOINT))
            .addHeader("accept", "application/json")
            .addHeader("ClientId", HealthOnClient.clientId)
            .addHeader("ClientSecret", HealthOnClient.clientSecret)
            .post(body)
            .build()

        val totalBytes = ppgCsv.size + ecgCsv.size
        Log.d(
            TAG,
            """
            ▶ [Request] POST ${request.url}
              reqDate    = $finalReqDate
              userSno    = $finalUserSno
              userAge    = $finalUserAge
              ppgFile    = ${ppgCsv.size} bytes (${ppgCsv.size / 1024} KB)
              ecgFile    = ${ecgCsv.size} bytes (${ecgCsv.size / 1024} KB)
              totalBody  ≈ $totalBytes bytes (${totalBytes / 1024} KB)
            """.trimIndent()
        )

        val startMs = System.currentTimeMillis()

        HealthOnClient.client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startMs
            val resBody = response.body?.string().orEmpty()

            val level = if (response.isSuccessful) "D" else "E"
            val logMsg = """
            ◀ [Response] ${response.code} ${response.message} (${elapsedMs}ms)
              url        = ${response.request.url}
              body       = $resBody
            """.trimIndent()

            if (response.isSuccessful) Log.d(TAG, logMsg) else Log.e(TAG, logMsg)

            return Response(
                httpCode = response.code,
                body = resBody,
                success = response.isSuccessful,
            )
        }
    }
}
