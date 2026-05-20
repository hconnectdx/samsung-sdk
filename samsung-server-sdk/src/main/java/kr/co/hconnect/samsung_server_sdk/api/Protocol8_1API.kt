package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

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
 * 파일을 스트리밍으로 전송하여 대용량 데이터 전송 시 메모리 부담과 GC 일시정지를 줄인다.
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
     * @param ppgFile     PPG CSV 파일 (GREEN, IR, RED)
     * @param imuFile     IMU CSV 파일 (x, y, z)
     */
    @Throws(Exception::class)
    fun requestPost(
        reqDate: String? = null,
        userSno: Int? = null,
        sessionId: String,
        ppgFile: File,
        imuFile: File,
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
        require(ppgFile.exists() && ppgFile.length() > 0) { "ppgFile 이 없거나 비어 있습니다: ${ppgFile.path}" }
        require(imuFile.exists() && imuFile.length() > 0) { "imuFile 이 없거나 비어 있습니다: ${imuFile.path}" }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqDate", finalReqDate)
            .addFormDataPart("userSno", finalUserSno.toString())
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart("ppgFile", ppgFile.name, ppgFile.asRequestBody(CSV_MEDIA_TYPE))
            .addFormDataPart("imuFile", imuFile.name, imuFile.asRequestBody(CSV_MEDIA_TYPE))
            .build()

        val request = Request.Builder()
            .url(HealthOnClient.absoluteUrl(ENDPOINT))
            .addHeader("accept", "application/json")
            .addHeader("ClientId", HealthOnClient.clientId)
            .addHeader("ClientSecret", HealthOnClient.clientSecret)
            .post(body)
            .build()

        Log.d(
            TAG,
            """
            ▶ [Request] POST ${request.url}
              reqDate    = $finalReqDate
              userSno    = $finalUserSno
              sessionId  = $sessionId
              ppgFile    = ${ppgFile.length()} bytes (${ppgFile.length() / 1024} KB)
              imuFile    = ${imuFile.length()} bytes (${imuFile.length() / 1024} KB)
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
