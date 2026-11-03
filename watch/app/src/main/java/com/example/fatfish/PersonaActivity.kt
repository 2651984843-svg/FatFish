package com.example.fatfish

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 人格选择子页面：列出 6 套大肥鱼人格，点选即切换（记忆按人格独立）。
 * 底部提供「清空当前人格记忆」，相当于给这个人格开个新对话。
 */
class PersonaActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var currentId = Personas.DEFAULT_ID
    /** 已应用的方向设置（缓存，避免重复设置触发多余旋转动画） */
    private var appliedAutoRotate: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽早应用方向设置，减少"先竖屏再转"的多余动画
        applyOrientation()

        setContentView(R.layout.activity_persona)

        // 返回：手表无实体返回键，顶部返回按钮回设置页（侧滑返回仍可用）
        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        container = findViewById(R.id.personaContainer)
        currentId = DeepSeekClient.getPersonaId(this)

        renderList()

        findViewById<TextView>(R.id.clearButton).setOnClickListener {
            val name = Personas.byId(currentId).name
            AlertDialog.Builder(this)
                .setTitle("清空「$name」的记忆")
                .setMessage(R.string.persona_clear_confirm)
                .setPositiveButton("清空") { _, _ ->
                    DeepSeekClient.clearPersonaHistory(this, currentId)
                    Toast.makeText(this, R.string.persona_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 动态渲染 6 个人格项，选中项高亮 */
    private fun renderList() {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val margin = (6 * density).toInt()

        for (p in Personas.ALL) {
            val selected = p.id == currentId
            val item = TextView(this)
            item.text = (if (selected) "✓ " else "") + p.name + "\n" + p.desc
            item.textSize = 15f
            item.setTextColor(if (selected) Color.WHITE else Color.parseColor("#BBD0E8"))
            item.setBackgroundColor(
                if (selected) Color.parseColor("#334A90D9") else Color.parseColor("#22FFFFFF")
            )
            item.setPadding(pad, pad, pad, pad)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, margin, 0, margin)
            item.layoutParams = lp

            item.setOnClickListener {
                currentId = p.id
                DeepSeekClient.savePersonaId(this, currentId)
                Toast.makeText(this, "已切换为「${p.name}」", Toast.LENGTH_SHORT).show()
                renderList()
            }
            container.addView(item)
        }
    }

    /** 应用「随重力方向旋转」设置：开=随重力自动横竖屏，关=固定竖屏。仅设置变化时生效，避免重复设置触发多余动画 */
    private fun applyOrientation() {
        val auto = DeepSeekClient.getAutoRotate(this)
        if (auto == appliedAutoRotate) return
        appliedAutoRotate = auto
        requestedOrientation = if (auto) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
