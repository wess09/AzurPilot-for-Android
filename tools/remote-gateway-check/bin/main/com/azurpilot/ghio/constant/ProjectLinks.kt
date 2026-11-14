package com.azurpilot.ghio.constant

/**
 * 项目对外的固定 GitHub 链接：反馈入口与机型支持清单
 *
 * Release 资源地址不走这里——那些要经镜像前缀拼装，见
 * [com.azurpilot.ghio.update.ReleaseUrls]；本类只收打开浏览器的直达页
 *
 * Fixed outbound GitHub links of the project: the feedback entry and the device
 * support list.
 *
 * Release asset URLs do not live here — those are assembled through a mirror
 * prefix, see [com.azurpilot.ghio.update.ReleaseUrls]; this class only holds
 * pages opened directly in a browser.
 */
object ProjectLinks {
    /** Issue 列表页：设置页「反馈 Bug」的落点 / The issue list: where the settings "report a bug" row lands. */
    const val ISSUES = "https://github.com/wess09/AzurPilot-for-Android/issues"

    /** QQ 反馈群：设置页「加群反馈」的落点 / The QQ feedback group, opened from the settings "join feedback group" row. */
    const val QQ_FEEDBACK_GROUP = "https://qm.qq.com/q/mrfxp743zc"

    /** Issue #1「机型支持列表」：首启弹窗引导用户登记机型 / Issue #1, the device support list: where the first-launch dialog sends users. */
    const val ISSUE_DEVICE_SUPPORT = "$ISSUES/1"
}
