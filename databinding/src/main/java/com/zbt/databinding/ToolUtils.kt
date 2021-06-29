package com.zbt.databinding

/**
 * Description:
 * @Author: zhuqt13
 * Date: 2021/6/28 17:06
 */
object ToolUtils {

    @JvmStatic
    fun bolToString(card: Boolean): String {
        return if (card) "已打卡" else "未打卡"
    }
}