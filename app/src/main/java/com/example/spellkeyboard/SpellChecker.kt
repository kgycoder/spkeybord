package com.example.spellkeyboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 한 건의 맞춤법 오류: 원문 구간 [start,end) 와 교정 제안 */
data class SpellError(
    val original: String,
    val suggestion: String,
    val start: Int,
    val end: Int
)

/**
 * 부산대학교 한국어 맞춤법/문법 검사기(https://speller.cs.pusan.ac.kr)의 공개 결과 페이지를
 * 파싱하는 비공식 클라이언트. 키 발급/설정 없이 바로 쓸 수 있지만, 사이트 쪽 응답 포맷이
 * 바뀌면 파싱이 깨질 수 있다 — 그럴 경우 이 파일의 parseErrors()만 교체하면 된다.
 * 다른 맞춤법 API(네이버/다음 등)로 바꾸고 싶다면 이 파일만 수정하면 된다.
 */
object SpellChecker {

    private const val ENDPOINT = "https://speller.cs.pusan.ac.kr/speller/results"

    suspend fun check(text: String): List<SpellError> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        try {
            val body = "text1=" + URLEncoder.encode(text, "UTF-8")
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SpellKeyboard/1.0")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseHtml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            parseErrors(responseHtml, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val BLOCK_REGEX = Regex("\\{[^{}]*\\}")
    private fun field(block: String, name: String): String? {
        val m = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(block)
            ?: Regex("'$name'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)'").find(block)
        return m?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim()
    }
    private fun intField(block: String, name: String): Int? {
        val m = Regex("\"$name\"\\s*:\\s*(\\d+)").find(block)
            ?: Regex("'$name'\\s*:\\s*(\\d+)").find(block)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseErrors(html: String, originalText: String): List<SpellError> {
        val dataSection = Regex("data\\s*=\\s*\\[(.*?)\\];", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val results = mutableListOf<SpellError>()
        for (block in BLOCK_REGEX.findAll(dataSection).map { it.value }) {
            val orgStr = field(block, "orgStr") ?: field(block, "str") ?: continue
            val candRaw = field(block, "candWord") ?: continue
            val suggestion = candRaw.split("|", ",").firstOrNull()?.trim().orEmpty()
            if (suggestion.isEmpty() || orgStr.isEmpty()) continue

            val start = intField(block, "start")
            val end = intField(block, "end")
            val range = if (start != null && end != null) {
                start to end
            } else {
                val idx = originalText.indexOf(orgStr)
                if (idx < 0) continue
                idx to (idx + orgStr.length)
            }
            results.add(SpellError(orgStr, suggestion, range.first, range.second))
        }
        return results
    }
}
