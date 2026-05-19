package kr.co.hconnect.samsung_server_sdk.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 헬스온(HealthOn) 서버 통신용 HTTP 클라이언트.
 *
 * polihealth-sdk-android-v2 의 PoliClient 와 동일한 역할.
 * - baseUrl, clientId, clientSecret 헤더 설정
 * - 측정 결과 전송 시 함께 보낼 userSno, userAge 보관
 *
 * 사용 방법:
 * ```
 * HealthOnClient.init(
 *     baseUrl = "https://mapi-stg.health-on.co.kr/",
 *     clientId = "...",
 *     clientSecret = "..."
 * )
 * HealthOnClient.setUser(userSno = 1234, userAge = 30)
 * ```
 */
object HealthOnClient {

    private const val TAG = "HealthOnClient"

    @Volatile
    private var initialized: Boolean = false

    internal lateinit var client: OkHttpClient
        private set

    internal var baseUrl: String = ""
        private set

    internal var clientId: String = ""
        private set

    internal var clientSecret: String = ""
        private set

    @Volatile
    var userSno: Int = 4
        private set

    @Volatile
    var userAge: Int = 30
        private set

    val isInitialized: Boolean get() = initialized

    /**
     * HealthOnClient 초기화.
     *
     * @param baseUrl  ex) "https://mapi-stg.health-on.co.kr/"
     * @param clientId 서버에서 발급한 ClientId
     * @param clientSecret 서버에서 발급한 ClientSecret
     * @param connectTimeoutMs 연결 타임아웃(ms) (기본 15초)
     * @param readTimeoutMs    응답 타임아웃(ms) (기본 30초)
     * @param writeTimeoutMs   요청 전송 타임아웃(ms) (기본 60초, 대용량 멀티파트 업로드 여유)
     */
    @Synchronized
    fun init(
        baseUrl: String,
        clientId: String,
        clientSecret: String,
        connectTimeoutMs: Long = 15_000L,
        readTimeoutMs: Long = 30_000L,
        writeTimeoutMs: Long = 60_000L,
    ) {
        this.baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        this.clientId = clientId
        this.clientSecret = clientSecret

        // ⚠️ BODY 레벨은 절대 사용 금지.
        // 우리는 PPG/ECG/IMU CSV 멀티파트(수백 KB) 를 업로드하는데,
        // HttpLoggingInterceptor.BODY 는 요청 바디를 통째로 logcat 에 출력하여
        // wall-clock 을 크게 늘리고 결국 Azure Gateway 의 idle timeout(≈20초)을 넘겨
        // 504 Gateway Time-out 을 유발한다.
        // 응답 바디는 아래의 responseBodyLogInterceptor 가 별도로 잘라서 출력한다.
        val logging = HttpLoggingInterceptor { message -> Log.d(TAG, message) }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(logging)
            .addInterceptor(responseBodyLogInterceptor())
            .build()

        initialized = true
        Log.d(
            TAG,
            "HealthOnClient initialized. baseUrl=$baseUrl " +
                    "(connectTo=${connectTimeoutMs}ms, readTo=${readTimeoutMs}ms, writeTo=${writeTimeoutMs}ms)"
        )
    }

    /**
     * 측정 결과 전송에 사용할 사용자 정보 설정.
     */
    fun setUser(userSno: Int, userAge: Int) {
        this.userSno = userSno
        this.userAge = userAge
        Log.d(TAG, "User set. userSno=$userSno, userAge=$userAge")
    }

    /**
     * baseUrl 기준으로 endpoint 의 절대 URL을 생성한다.
     */
    internal fun absoluteUrl(endpoint: String): String {
        val ep = endpoint.removePrefix("/")
        return "$baseUrl$ep"
    }

    internal fun checkInitialized() {
        check(initialized) { "HealthOnClient.init() 을 먼저 호출하세요." }
    }

    /**
     * 응답 바디를 별도로 로그에 남기는 인터셉터.
     * multipart 업로드처럼 요청 바디가 매우 큰 경우
     * HttpLoggingInterceptor(BODY) 는 요청 바디까지 찍어 logcat 이 넘칠 수 있으므로,
     * 응답 바디만 최대 [MAX_BODY_LOG] 자로 잘라서 출력한다.
     */
    private fun responseBodyLogInterceptor(maxBodyLog: Int = 2_000): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val startNs = System.nanoTime()

        val response = chain.proceed(request)

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        val rawBody = response.body

        if (rawBody != null) {
            val source = rawBody.source().also { it.request(Long.MAX_VALUE) }
            val bodyStr = source.buffer.clone().readString(Charsets.UTF_8)
            val truncated = if (bodyStr.length > maxBodyLog) {
                bodyStr.take(maxBodyLog) + "\n… (총 ${bodyStr.length}자 중 ${maxBodyLog}자 표시)"
            } else {
                bodyStr
            }

            Log.d(
                TAG,
                "◀ ${response.code} ${request.method} ${request.url} " +
                        "(${elapsedMs}ms)\n$truncated"
            )
        } else {
            Log.d(
                TAG,
                "◀ ${response.code} ${request.method} ${request.url} " +
                        "(${elapsedMs}ms) [body=null]"
            )
        }

        response
    }
}
