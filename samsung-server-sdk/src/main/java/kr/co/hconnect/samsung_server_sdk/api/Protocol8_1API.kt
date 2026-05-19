package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST {baseUrl}/poli/sleep/protocol8-1
 *
 * Form fields:
 *  - reqDate   (yyyyMMddHHmmss, 14자)
 *  - userSno
 *  - sessionId (yyyyMMdd_HHmmss, 15자)
 *  - ppgFile   (CSV: GREEN, IR, RED)
 *  - imuFile   (CSV: x, y, z)
 *
 * 수면(SLEEP) 측정 종료 시점에 누적된 PPG / IMU(ACC) 데이터를 전송한다.
 */
object Protocol8_1API {

    private const val TAG = "Protocol8_1API"
    private const val ENDPOINT = "poli/sleep/protocol8-1"

    private val CSV_MEDIA_TYPE = "text/csv".toMediaType()

    data class Response(
        val httpCode: Int,
        val body: String,
        val success: Boolean,
    )

    /**
     * Protocol 8-1 측정 결과 전송 (블로킹 호출).
     *
     * @param reqDate     수집일시 (yyyyMMddHHmmss) — null 이면 현재 시간으로 자동 채움
     * @param userSno     사용자 번호 — null 이면 HealthOnClient.userSno 사용
     * @param sessionId   세션 ID (yyyyMMdd_HHmmss, 15자)
     * @param ppgCsv      PPG CSV 바이트 (GREEN, IR, RED)
     * @param imuCsv      IMU CSV 바이트 (x, y, z)
     * @param ppgFileName 첨부 파일명 (기본 "ppg.csv")
     * @param imuFileName 첨부 파일명 (기본 "imu.csv")
     */
    @Throws(Exception::class)
    fun requestPost(
        reqDate: String? = null,
        userSno: Int? = null,
        sessionId: String,
        ppgCsv: ByteArray,
        imuCsv: ByteArray,
        ppgFileName: String = "ppg.csv",
        imuFileName: String = "imu.csv",
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
        require(ppgCsv.isNotEmpty()) { "ppgCsv 가 비어 있습니다." }
        require(imuCsv.isNotEmpty()) { "imuCsv 가 비어 있습니다." }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqDate", finalReqDate)
            .addFormDataPart("userSno", finalUserSno.toString())
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart(
                "ppgFile",
                ppgFileName,
                ppgCsv.toRequestBody(CSV_MEDIA_TYPE)
            )
            .addFormDataPart(
                "imuFile",
                imuFileName,
                imuCsv.toRequestBody(CSV_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url(HealthOnClient.absoluteUrl(ENDPOINT))
            .addHeader("accept", "application/json")
            .addHeader("ClientId", HealthOnClient.clientId)
            .addHeader("ClientSecret", HealthOnClient.clientSecret)
            .post(body)
            .build()

        val totalBytes = ppgCsv.size + imuCsv.size
        Log.d(
            TAG,
            """
            ▶ [Request] POST ${request.url}
              reqDate    = $finalReqDate
              userSno    = $finalUserSno
              sessionId  = $sessionId
              ppgFile    = ${ppgCsv.size} bytes (${ppgCsv.size / 1024} KB)
              imuFile    = ${imuCsv.size} bytes (${imuCsv.size / 1024} KB)
              totalBody  ≈ $totalBytes bytes (${totalBytes / 1024} KB)
            """.trimIndent()
        )

        val startMs = System.currentTimeMillis()

        HealthOnClient.client.newCall(request).execute().use { response ->
            val elapsedMs = System.currentTimeMillis() - startMs
            val resBody = response.body?.string().orEmpty()

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
