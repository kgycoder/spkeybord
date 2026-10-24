package com.example.spellkeyboard

/**
 * 표준 2벌식 자모 입력을 완성형 한글 음절로 조합하는 상태 기계.
 * 완벽한 맞춤법 규칙 엣지케이스까지 커버하진 않지만 일반적인 타이핑 흐름은 자연스럽게 처리한다.
 */
class HangulComposer {

    data class Result(val commit: String?, val composing: String)

    companion object {
        private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        // index 0 = 받침 없음
        private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val JUNG_COMPOUND = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ'
        )
        private val JONG_COMPOUND = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ'
        )
        private val JONG_SPLIT = JONG_COMPOUND.entries.associate { it.value to it.key }

        fun isConsonant(c: Char) = CHO.indexOf(c) >= 0
        fun isVowel(c: Char) = JUNG.indexOf(c) >= 0
    }

    private var cho = -1
    private var jung = -1
    private var jong = 0 // 0 = 없음

    private fun compose(): String {
        if (cho == -1) return ""
        if (jung == -1) return CHO[cho].toString()
        val code = 0xAC00 + (cho * 21 + jung) * 28 + jong
        return code.toChar().toString()
    }

    fun hasComposing() = cho != -1

    /** 현재 조합 중인 미리보기 텍스트 (없으면 빈 문자열) */
    fun preview(): String = compose()

    fun inputConsonant(c: Char): Result {
        val choIdx = CHO.indexOf(c)
        if (choIdx < 0) return Result(null, compose())

        return when {
            cho == -1 -> {
                cho = choIdx; jung = -1; jong = 0
                Result(null, compose())
            }
            jung == -1 -> {
                // 초성만 있는 상태에서 자음이 또 들어옴 -> 이전 블록 확정, 새 블록 시작
                val finished = compose()
                cho = choIdx; jung = -1; jong = 0
                Result(finished, compose())
            }
            jong == 0 -> {
                val jongIdx = JONG.indexOf(c)
                if (jongIdx > 0) {
                    jong = jongIdx
                    Result(null, compose())
                } else {
                    val finished = compose()
                    cho = choIdx; jung = -1; jong = 0
                    Result(finished, compose())
                }
            }
            else -> {
                val curJongChar = JONG[jong]
                val combined = JONG_COMPOUND[curJongChar to c]
                if (combined != null) {
                    jong = JONG.indexOf(combined)
                    Result(null, compose())
                } else {
                    val finished = compose()
                    cho = choIdx; jung = -1; jong = 0
                    Result(finished, compose())
                }
            }
        }
    }

    fun inputVowel(v: Char): Result {
        val jungIdx = JUNG.indexOf(v)
        if (jungIdx < 0) return Result(null, compose())

        return when {
            cho == -1 -> {
                // 초성 없이 모음만: 독립 모음으로 바로 커밋
                Result(v.toString(), "")
            }
            jung == -1 -> {
                jung = jungIdx
                Result(null, compose())
            }
            jong == 0 -> {
                val curJungChar = JUNG[jung]
                val combined = JUNG_COMPOUND[curJungChar to v]
                if (combined != null) {
                    jung = JUNG.indexOf(combined)
                    Result(null, compose())
                } else {
                    val finished = compose()
                    cho = -1; jung = -1; jong = 0
                    // 새 모음은 초성 없이 독립 커밋 후 리셋
                    Result(finished + v, "").also { reset() }
                }
            }
            else -> {
                // 받침을 다음 음절의 초성으로 넘김
                val curJongChar = JONG[jong]
                val split = JONG_SPLIT[curJongChar]
                val finished: String
                val newChoChar: Char
                if (split != null) {
                    // 겹받침: 앞부분은 현재 음절에 남기고 뒷부분을 다음 초성으로
                    jong = JONG.indexOf(split.first)
                    finished = compose()
                    newChoChar = split.second
                } else {
                    val movedChar = curJongChar
                    jong = 0
                    finished = compose()
                    newChoChar = movedChar
                }
                val newChoIdx = CHO.indexOf(newChoChar)
                cho = newChoIdx
                jung = jungIdx
                jong = 0
                Result(finished, compose())
            }
        }
    }

    /** 숫자/기호/스페이스 등 한글이 아닌 입력이 들어올 때 현재 조합을 확정한다 */
    fun flush(): String {
        val finished = compose()
        reset()
        return finished
    }

    fun backspace(): Result {
        when {
            jong != 0 -> jong = 0
            jung != -1 -> jung = -1
            cho != -1 -> cho = -1
        }
        return Result(null, compose())
    }

    fun reset() {
        cho = -1; jung = -1; jong = 0
    }
}
