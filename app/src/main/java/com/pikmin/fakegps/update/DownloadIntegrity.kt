package com.pikmin.fakegps.update

object DownloadIntegrity {
    fun verify(actualSize: Long, expectedSize: Long, actualSha256: String, expectedSha256: String?) {
        require(actualSize > 0) { "下載檔案為空" }
        require(expectedSize <= 0 || actualSize == expectedSize) { "下載大小與發布資訊不符" }
        if (expectedSha256 != null) {
            require(Regex("[a-fA-F0-9]{64}").matches(expectedSha256)) { "發布雜湊格式錯誤" }
            require(actualSha256.equals(expectedSha256, ignoreCase = true)) { "APK SHA-256 驗證失敗" }
        }
    }
}
