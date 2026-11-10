package com.quietsentry.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 下拉刷新用的 ScrollView（T04742 / docs/07 §4.3）。
 *
 * 为什么不用 setOnTouchListener 而是重写 onInterceptTouchEvent：
 * 列表里每张卡片都是 clickable，会吞掉 ACTION_DOWN；而且在列表顶部向下拖时，
 * ScrollView 因为“没有可上滚的内容”不会拦截这次手势，于是事件一直留在卡片上，
 * onTouchEvent 里的OnTouchListener 根本收不到 —— 结果就是“抓卡片下拉刷新”完全失效，
 * 只有抓到卡片之间的空白才能触发。onInterceptTouchEvent 在事件下发给孩子之前一定会被调用，
 * 所以放在这里检测“已在顶部 + 向下拖动超阈值”最稳妥，且不影响卡片点击与正常滚动。
 */
class PullToRefreshScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    /** 下拉超过该距离（px）即触发一次刷新 */
    var pullThresholdPx: Float = 120f

    /** 下拉到顶触发；回调里做轮询与反馈文案 */
    var onPullToRefresh: (() -> Unit)? = null

    private var startY = 0f
    private var armed = false
    private var triggered = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                // 仅在列表已到顶时才武装：滚动中途的下拉不应误触发刷新
                armed = scrollY == 0
                triggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed && !triggered && scrollY == 0 &&
                    ev.y - startY > pullThresholdPx
                ) {
                    triggered = true
                    onPullToRefresh?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                armed = false
                triggered = false
            }
        }
        // 始终交回父类处理，保证卡片点击与正常滚动不受影响；
        // 这里只是“顺手”在拦截点检测下拉，不改变事件归属。
        return super.onInterceptTouchEvent(ev)
    }
}
