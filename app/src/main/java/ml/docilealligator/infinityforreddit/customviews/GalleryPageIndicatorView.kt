package ml.docilealligator.infinityforreddit.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** A compact, non-interactive position indicator shared by both gallery viewers. */
class GalleryPageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val inactiveDotSize = dp(4f)
    private val selectedDotWidth = dp(8f)
    private val dotGap = dp(4f)
    private val minimumDotSize = dp(2f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pageCount = 0
    private var currentPage = 0

    init {
        setPadding(dp(8f).toInt(), dp(5f).toInt(), dp(8f).toInt(), dp(5f).toInt())
        background = GradientDrawable().apply {
            setColor(Color.argb(150, 0, 0, 0))
            cornerRadius = dp(12f)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
    }

    fun setPageCount(count: Int) {
        pageCount = count.coerceAtLeast(0)
        currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        visibility = if (pageCount > 1) VISIBLE else GONE
        updateAccessibilityDescription()
        requestLayout()
        invalidate()
    }

    fun setCurrentPage(page: Int) {
        if (pageCount <= 1) return
        val nextPage = page.coerceIn(0, pageCount - 1)
        if (nextPage == currentPage) return
        currentPage = nextPage
        updateAccessibilityDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visibleDots = visibleDotCount()
        val contentWidth = if (visibleDots == 0) {
            0f
        } else {
            selectedDotWidth + (visibleDots - 1) * (inactiveDotSize + dotGap)
        }
        val desiredWidth = (paddingLeft + paddingRight + contentWidth).toInt()
        val desiredHeight = paddingTop + paddingBottom + selectedDotWidth.toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visibleDots = visibleDotCount()
        if (visibleDots == 0) return

        val startPage = windowStart(visibleDots)
        val selectedIndex = currentPage - startPage
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val desiredWidth = selectedDotWidth + (visibleDots - 1) * (inactiveDotSize + dotGap)
        val scale = min(1f, availableWidth / desiredWidth)
        val inactiveSize = max(minimumDotSize, inactiveDotSize * scale)
        val selectedSize = max(inactiveSize, selectedDotWidth * scale)
        val gap = if (visibleDots > 1) {
            max(0f, min(dotGap * scale, (availableWidth - selectedSize - (visibleDots - 1) * inactiveSize) / (visibleDots - 1)))
        } else {
            0f
        }
        val totalWidth = selectedSize + (visibleDots - 1) * (inactiveSize + gap)
        var left = (width - totalWidth) / 2f
        val centerY = (paddingTop + height - paddingBottom) / 2f

        repeat(visibleDots) { index ->
            val size = if (index == selectedIndex) selectedSize else inactiveSize
            val continuesBefore = index == 0 && startPage > 0
            val continuesAfter = index == visibleDots - 1 && startPage + visibleDots < pageCount
            dotPaint.color = Color.WHITE
            dotPaint.alpha = if (continuesBefore || continuesAfter) 110 else if (index == selectedIndex) 255 else 150
            canvas.drawRoundRect(
                RectF(left, centerY - size / 2f, left + size, centerY + size / 2f),
                size / 2f,
                size / 2f,
                dotPaint,
            )
            left += size + gap
        }
    }

    private fun visibleDotCount(): Int = min(pageCount, MAX_VISIBLE_DOTS)

    private fun windowStart(visibleDots: Int): Int =
        (currentPage - visibleDots / 2).coerceIn(0, (pageCount - visibleDots).coerceAtLeast(0))

    private fun updateAccessibilityDescription() {
        contentDescription = if (pageCount > 1) {
            resources.getString(R.string.gallery_page_indicator_description, currentPage + 1, pageCount)
        } else {
            null
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val MAX_VISIBLE_DOTS = 15
    }
}
