package com.freespeak.ime.model

internal object FunAsrMltLanguages {
    private val names = mapOf(
        "zh" to "中文", "en" to "英文", "yue" to "粤语", "ja" to "日文", "ko" to "韩文",
        "vi" to "越南语", "id" to "印尼语", "th" to "泰语", "ms" to "马来语", "fil" to "菲律宾语",
        "ar" to "阿拉伯语", "hi" to "印地语", "bg" to "保加利亚语", "hr" to "克罗地亚语", "cs" to "捷克语",
        "da" to "丹麦语", "nl" to "荷兰语", "et" to "爱沙尼亚语", "fi" to "芬兰语", "el" to "希腊语",
        "hu" to "匈牙利语", "ga" to "爱尔兰语", "lv" to "拉脱维亚语", "lt" to "立陶宛语", "mt" to "马耳他语",
        "pl" to "波兰语", "pt" to "葡萄牙语", "ro" to "罗马尼亚语", "sk" to "斯洛伐克语", "sl" to "斯洛文尼亚语",
        "sv" to "瑞典语",
    )
    val supported = names.keys.toList()

    fun nameFor(code: String): String? = names[code]

    fun transcriptionPrompt(code: String): String = nameFor(code)?.let { "语音转写成$it：" } ?: "语音转写："
}
