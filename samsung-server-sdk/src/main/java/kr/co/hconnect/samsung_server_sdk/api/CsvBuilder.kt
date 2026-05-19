package kr.co.hconnect.samsung_server_sdk.api

import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples

/**
 * 워치에서 수신한 PPG / ECG SensorSamples 를 CSV 바이트로 변환한다.
 *
 * 서버 스펙:
 *  - ppgFile: PPG CSV (GREEN, IR, RED)
 *  - ecgFile: ECG CSV (value, lead_off, max_threshold_mv, min_threshold_mv, seq)
 *
 * 입력 샘플은 SensorBufferProto 의 `SensorSamples` 리스트로,
 * - PPG_GREEN_25 / PPG_GREEN_100 두 가지 모두 PPG 로 합쳐서 처리한다.
 * - ECG 는 `ecgData` oneof 필드를 사용한다.
 */
internal object CsvBuilder {

    private const val EOL = "\n"

    fun buildPpgCsv(samples: List<SensorSamples>): ByteArray {
        val sb = StringBuilder(64 + samples.size * 24)
        sb.append("GREEN,IR,RED").append(EOL)
        for (s in samples) {
            when {
                s.hasPpgGreen25Data() -> with(s.ppgGreen25Data) {
                    sb.append(green25).append(',')
                        .append(ir25).append(',')
                        .append(red25).append(EOL)
                }
                s.hasPpgGreen100Data() -> with(s.ppgGreen100Data) {
                    sb.append(green100).append(',')
                        .append(ir100).append(',')
                        .append(red100).append(EOL)
                }
                else -> Unit
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildEcgCsv(samples: List<SensorSamples>): ByteArray {
        val sb = StringBuilder(80 + samples.size * 48)
        sb.append("value,lead_off,max_threshold_mv,min_threshold_mv,seq").append(EOL)
        for (s in samples) {
            if (!s.hasEcgData()) continue
            with(s.ecgData) {
                sb.append(value).append(',')
                    .append(leadOff).append(',')
                    .append(maxThresholdMv).append(',')
                    .append(minThresholdMv).append(',')
                    .append(seq).append(EOL)
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * IMU (ACC) CSV — protocol8-1 의 `imuFile` 용.
     * 헤더: `x, y, z`
     */
    fun buildImuCsv(samples: List<SensorSamples>): ByteArray {
        val sb = StringBuilder(16 + samples.size * 18)
        sb.append("x,y,z").append(EOL)
        for (s in samples) {
            if (!s.hasAcc25Data()) continue
            with(s.acc25Data) {
                sb.append(x).append(',')
                    .append(y).append(',')
                    .append(z).append(EOL)
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
