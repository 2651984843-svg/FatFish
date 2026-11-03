package com.example.fatfish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 大肥鱼（鲸鱼女仆版）：按 [FishState] 切换 6 张表情差分图，
 * 代码只做轻量动画——上下浮动、呼吸缩放、说话摇摆、思考冒泡。
 * 图片负责卖萌，动画负责让图片"活"起来，对小屏手表性能友好。
 */
class FishView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state: FishState = FishState.IDLE
    private var stateChangedAt = 0f

    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sprites = mutableMapOf<FishState, Bitmap>()
    private val matrix = Matrix()

    private var lastFrame = 0L
    private var spriteSizeFactor = 0.78f   // 当前精灵图显示高度系数（缓动用）
    private var userScale = 1.0f           // 用户调节的整体缩放倍率（默认 1.0）
    private val rnd = Random()
    private val bubbles = mutableListOf<Bubble>()

    private class Bubble(var x: Float, var y: Float, val r: Float, val speed: Float, val phase: Float)

    init {
        spritePaint.alpha = 255
        bubblePaint.color = 0x99FFFFFF.toInt()
        bubblePaint.style = Paint.Style.STROKE
        bubblePaint.strokeWidth = 2f

        sprites[FishState.IDLE] = decode(R.drawable.fish_idle)
        sprites[FishState.LISTENING] = decode(R.drawable.fish_listening)
        sprites[FishState.THINKING] = decode(R.drawable.fish_thinking)
        sprites[FishState.SPEAKING] = decode(R.drawable.fish_speaking)
        sprites[FishState.HAPPY] = decode(R.drawable.fish_happy)
        sprites[FishState.SLEEPY] = decode(R.drawable.fish_sleepy)
    }

    private fun decode(resId: Int): Bitmap =
        android.graphics.BitmapFactory.decodeResource(resources, resId)

    fun setState(s: FishState) {
        if (state != s) {
            state = s
            stateChangedAt = SystemClock.uptimeMillis() / 1000f
            if (s == FishState.HAPPY) {
                postDelayed({ if (state == FishState.HAPPY) setState(FishState.IDLE) }, 2600)
            }
        }
    }

    /** 用户调节的整体缩放倍率（相对默认大小），设置后立即重绘 */
    fun setUserScale(s: Float) {
        val clamped = s.coerceIn(0.3f, 2.0f)
        if (abs(clamped - userScale) > 0.001f) {
            userScale = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (lastFrame == 0L) lastFrame = now
        val dt = (now - lastFrame) / 1000f
        lastFrame = now
        val t = now / 1000f

        if (width <= 0 || height <= 0) return

        drawWater(canvas, t)
        updateBubbles(dt)
        drawBubbles(canvas, t)
        drawSprite(canvas, t, dt)

        postInvalidateOnAnimation()
    }

    private fun drawWater(canvas: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        waterPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            0xFF17324F.toInt(), 0xFF0B1B2E.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, waterPaint)
        waterPaint.shader = null

        // 缓慢流动的光带，营造"在水里"的感觉
        lightPaint.color = 0x12FFFFFF.toInt()
        for (i in 0 until 3) {
            val y = h * 0.18f + i * h * 0.24f + sin(t * 0.3f + i) * 6f
            canvas.drawOval(w * 0.08f, y, w * 0.92f, y + 16f, lightPaint)
        }
    }

    private fun updateBubbles(dt: Float) {
        // 思考时头顶冒泡泡，说话时偶尔吐小泡
        if (state == FishState.THINKING && bubbles.size < 6 && rnd.nextFloat() < dt * 4f) {
            bubbles.add(
                Bubble(
                    width * 0.5f + rnd.nextFloat() * 40f - 20f,
                    height * 0.16f,
                    rnd.nextFloat() * 5f + 2f,
                    rnd.nextFloat() * 40f + 28f,
                    rnd.nextFloat() * 6f
                )
            )
        } else if (state == FishState.SPEAKING && bubbles.size < 4 && rnd.nextFloat() < dt * 1.5f) {
            bubbles.add(
                Bubble(
                    width * 0.78f,
                    height * 0.34f,
                    rnd.nextFloat() * 4f + 2f,
                    rnd.nextFloat() * 30f + 20f,
                    rnd.nextFloat() * 6f
                )
            )
        }
        val it = bubbles.iterator()
        while (it.hasNext()) {
            val bub = it.next()
            bub.y -= bub.speed * dt
            if (bub.y < -20f) it.remove()
        }
    }

    private fun drawBubbles(canvas: Canvas, t: Float) {
        for (b in bubbles) {
            canvas.drawCircle(b.x + sin(t * 3f + b.phase) * 4f, b.y, b.r, bubblePaint)
        }
    }

    private fun drawSprite(canvas: Canvas, t: Float, dt: Float) {
        val bmp = sprites[state] ?: sprites[FishState.IDLE] ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        // 呆滞脸是特写头像，显示小一点；尺寸变化做缓动避免跳变
        val targetFactor = if (state == FishState.SLEEPY) 0.55f else 0.78f
        spriteSizeFactor += (targetFactor - spriteSizeFactor) * min(1f, dt * 6f)
        val drawH = h * spriteSizeFactor * userScale
        val scale = drawH / bmp.height
        val drawW = bmp.width * scale
        val cx = w / 2f
        val baseCy = h * 0.50f
        val st = t - stateChangedAt

        // 状态差异化的轻动画
        var bobY = sin(t * 1.2f) * h * 0.012f          // 默认：缓慢浮动
        var scaleX = 1f
        var scaleY = 1f + sin(t * 1.6f) * 0.012f       // 默认：呼吸
        var rotation = 0f

        when (state) {
            FishState.IDLE -> {}
            FishState.LISTENING -> {
                // 前倾凑近：略微放大 + 轻快小浮
                scaleX = 1.04f; scaleY = 1.04f
                bobY = sin(t * 2.0f) * h * 0.008f
            }
            FishState.THINKING -> {
                // 歪头思考：轻微左右摇 + 缓慢下沉感
                rotation = sin(t * 1.5f) * 3f
                bobY = sin(t * 0.9f) * h * 0.006f + h * 0.01f
            }
            FishState.SPEAKING -> {
                // 说话：跟随语速节奏摇摆起伏
                rotation = sin(t * 7.0f) * 2.5f
                scaleY = 1f + sin(t * 7.0f) * 0.02f
                bobY = sin(t * 3.5f) * h * 0.010f
            }
            FishState.HAPPY -> {
                // 开心：蹦跳 + 快速小摆
                bobY = -abs(sin(t * 4.0f)) * h * 0.045f
                rotation = sin(t * 5.0f) * 4f
            }
            FishState.SLEEPY -> {
                // 犯困：下沉 + 极慢呼吸
                bobY = h * 0.03f + sin(t * 0.7f) * h * 0.004f
                scaleY = 1f + sin(t * 0.7f) * 0.006f
            }
        }

        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        // 关键：把「目标高度/原图高度」的缩放比例乘进去，userScale 和 spriteSizeFactor 才真正生效
        matrix.postScale(scaleX * scale, scaleY * scale)
        matrix.postRotate(rotation)
        matrix.postTranslate(cx, baseCy + bobY)
        canvas.drawBitmap(bmp, matrix, spritePaint)
    }
}
