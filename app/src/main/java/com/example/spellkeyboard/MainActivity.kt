package com.example.spellkeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun title(text: String) = TextView(this).apply {
            this.text = text
            textSize = 22f
            setPadding(0, 0, 0, pad / 2)
        }

        fun body(text: String) = TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 0, 0, pad)
        }

        root.addView(title("맞춤법 키보드"))
        root.addView(
            body(
                "이 키보드는 입력 중 맞춤법 오류를 실시간으로 감지해 제안 카드를 보여줍니다.\n\n" +
                    "사용 전 딱 두 단계만 필요합니다 (Android 보안 정책상 앱이 대신 켤 수 없어요):\n" +
                    "1) 아래 버튼으로 키보드를 '활성화'\n" +
                    "2) 아래 버튼으로 입력 중 키보드를 '전환'\n\n" +
                    "사용법\n" +
                    "• 맞춤법 오류가 있으면 키보드 위 카드에 하이라이팅과 교정안이 표시됩니다.\n" +
                    "• Shift 키(⇧)를 빠르게 두 번 누르면 현재 제안된 교정이 자동 적용됩니다.\n" +
                    "• 맞춤법 검사는 온라인으로 동작합니다 (완전 오프라인 아님)."
            )
        )

        val enableBtn = Button(this).apply {
            text = "1. 키보드 활성화 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        val switchBtn = Button(this).apply {
            text = "2. 키보드 전환 (선택 창 열기)"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        root.addView(enableBtn)
        val spacer = TextView(this).apply { setPadding(0, pad / 3, 0, 0) }
        root.addView(spacer)
        root.addView(switchBtn)

        setContentView(ScrollView(this).apply {
            addView(root)
        })
    }
}
