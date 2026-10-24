package com.example.spellkeyboard

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpellCheckIME : InputMethodService() {

    private val composer = HangulComposer()
    private var shiftOn = false
    private var lastShiftTapAt = 0L
    private val doubleTapWindowMs = 320L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private var currentError: SpellError? = null

    // ---- views ----
    private lateinit var suggestionCard: LinearLayout
    private lateinit var oldWordView: TextView
    private lateinit var arrowView: TextView
    private lateinit var newWordView: TextView
    private val letterButtons = mutableListOf<Pair<TextView, Pair<String, String>>>() // (view, base to shift)
    private lateinit var shiftButton: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorRes(R_kb_bg))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        root.addView(buildSuggestionCard())
        root.addView(spacer(dp(6)))
        root.addView(buildKeyboard())
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composer.reset()
        hideSuggestion()
        shiftOn = false
        updateKeyLabels()
    }

    // ---------------------------------------------------------------------
    // UI building
    // ---------------------------------------------------------------------

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun hSpacer(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun colorRes(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

    // small aliases to keep code compact
    private val R_kb_bg get() = R.color.kb_bg
    private val R_key_text get() = R.color.key_text
    private val R_old get() = R.color.suggestion_word_old
    private val R_new get() = R.color.suggestion_word_new

    private fun buildSuggestionCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.suggestion_card_bg)
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setOnClickListener { applyCurrentCorrection() }
        }
        suggestionCard = card

        val cursorLeft = View(this).apply {
            setBackgroundResource(R.drawable.cursor_edge)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(20)).apply { rightMargin = dp(4) }
        }
        oldWordView = TextView(this).apply {
            setTextColor(colorRes(R_old))
            textSize = 15f
            setBackgroundResource(R.drawable.highlight_bg)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }
        val cursorRight = View(this).apply {
            setBackgroundResource(R.drawable.cursor_edge)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(20)).apply { leftMargin = dp(4) }
        }
        arrowView = TextView(this).apply {
            text = "  →  "
            setTextColor(colorRes(R_key_text))
            textSize = 15f
        }
        newWordView = TextView(this).apply {
            setTextColor(colorRes(R_new))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            text = "   (⇧ 두 번 탭하여 적용)"
            setTextColor(colorRes(R_old))
            textSize = 11f
        }

        card.addView(cursorLeft)
        card.addView(oldWordView)
        card.addView(cursorRight)
        card.addView(arrowView)
        card.addView(newWordView)
        card.addView(hint)
        return card
    }

    private val row1Base = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ")
    private val row1Shift = listOf("ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "ㅛ", "ㅕ", "ㅑ", "ㅒ", "ㅖ")
    private val row2Base = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ")
    private val row3Base = listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")

    private fun buildKeyboard(): View {
        letterButtons.clear()
        val kb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        kb.addView(buildRow(row1Base, row1Shift))
        kb.addView(spacer(dp(6)))
        kb.addView(buildRow(row2Base, row2Base))
        kb.addView(spacer(dp(6)))

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        shiftButton = specialKey("⇧", 1.4f) { onShiftTap() }
        row3.addView(shiftButton)
        row3.addView(hSpacer(dp(4)))
        val row3Letters = buildRow(row3Base, row3Base, weightEach = 1f)
        row3Letters.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 7f)
        row3.addView(row3Letters)
        row3.addView(hSpacer(dp(4)))
        row3.addView(specialKey("⌫", 1.4f) { onBackspace() })
        kb.addView(row3)
        kb.addView(spacer(dp(6)))

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(specialKey("스페이스", 5f) { onSpace() })
        row4.addView(hSpacer(dp(4)))
        row4.addView(specialKey("엔터", 1.6f) { onEnter() })
        kb.addView(row4)

        return kb
    }

    private fun buildRow(base: List<String>, shift: List<String>, weightEach: Float = 1f): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in base.indices) {
            val btn = letterKey(base[i])
            letterButtons.add(btn to (base[i] to shift[i]))
            btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weightEach).apply {
                marginEnd = if (i != base.lastIndex) dp(4) else 0
            }
            btn.setOnClickListener { onLetter(if (shiftOn) shift[i] else base[i]) }
            row.addView(btn)
        }
        return row
    }

    private fun letterKey(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(colorRes(R_key_text))
        setBackgroundResource(R.drawable.key_bg)
        height = dp(46)
        isClickable = true
        isFocusable = true
    }

    private fun specialKey(label: String, weight: Float, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(colorRes(R_key_text))
        setBackgroundResource(R.drawable.key_special_bg)
        layoutParams = LinearLayout.LayoutParams(0, dp(46), weight)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateKeyLabels() {
        shiftButton.setBackgroundResource(if (shiftOn) R.drawable.key_bg else R.drawable.key_special_bg)
        for ((view, pair) in letterButtons) {
            view.text = if (shiftOn) pair.second else pair.first
        }
    }

    // ---------------------------------------------------------------------
    // Input handling
    // ---------------------------------------------------------------------

    private fun onLetter(jamo: String) {
        val ic = currentInputConnection ?: return
        val c = jamo[0]
        val result = if (HangulComposer.isConsonant(c)) composer.inputConsonant(c) else composer.inputVowel(c)

        if (result.commit != null) ic.commitText(result.commit, 1)
        if (result.composing.isNotEmpty()) {
            ic.setComposingText(result.composing, 1)
        } else if (result.commit != null) {
            ic.finishComposingText()
        }

        if (shiftOn) { shiftOn = false; updateKeyLabels() }
        scheduleSpellCheck()
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        val finished = composer.flush()
        if (finished.isNotEmpty()) ic.commitText(finished, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        scheduleSpellCheck(immediate = true)
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        val finished = composer.flush()
        if (finished.isNotEmpty()) ic.commitText(finished, 1)
        ic.finishComposingText()
        ic.commitText("\n", 1)
        hideSuggestion()
    }

    private fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (composer.hasComposing()) {
            val result = composer.backspace()
            if (result.composing.isNotEmpty()) {
                ic.setComposingText(result.composing, 1)
            } else {
                ic.finishComposingText()
                ic.deleteSurroundingText(0, 0)
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        scheduleSpellCheck()
    }

    private fun onShiftTap() {
        val now = System.currentTimeMillis()
        if (now - lastShiftTapAt < doubleTapWindowMs) {
            applyCurrentCorrection()
            lastShiftTapAt = 0L
        } else {
            shiftOn = !shiftOn
            updateKeyLabels()
            lastShiftTapAt = now
        }
    }

    // ---------------------------------------------------------------------
    // Spell checking
    // ---------------------------------------------------------------------

    private fun scheduleSpellCheck(immediate: Boolean = false) {
        pendingCheck?.let { debounceHandler.removeCallbacks(it) }
        val r = Runnable { runSpellCheck() }
        pendingCheck = r
        debounceHandler.postDelayed(r, if (immediate) 250L else 700L)
    }

    private fun runSpellCheck() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(300, 0)?.toString() ?: return
        if (before.isBlank()) { hideSuggestion(); return }

        scope.launch {
            val errors = SpellChecker.check(before)
            if (errors.isEmpty()) {
                hideSuggestion()
                return@launch
            }
            // 커서에 가장 가까운(마지막) 오류를 우선 제안
            val err = errors.maxByOrNull { it.end }!!
            showSuggestion(err)
        }
    }

    private fun showSuggestion(err: SpellError) {
        currentError = err
        oldWordView.text = err.original
        newWordView.text = err.suggestion
        suggestionCard.visibility = View.VISIBLE
    }

    private fun hideSuggestion() {
        currentError = null
        suggestionCard.visibility = View.GONE
    }

    private fun applyCurrentCorrection() {
        val err = currentError ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(300, 0)?.toString() ?: return
        val idx = before.lastIndexOf(err.original)
        if (idx < 0) { hideSuggestion(); return }

        val tail = before.substring(idx + err.original.length)
        val deleteCount = before.length - idx

        ic.beginBatchEdit()
        ic.finishComposingText()
        ic.deleteSurroundingText(deleteCount, 0)
        ic.commitText(err.suggestion + tail, 1)
        ic.endBatchEdit()

        composer.reset()
        hideSuggestion()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingCheck?.let { debounceHandler.removeCallbacks(it) }
    }
}
