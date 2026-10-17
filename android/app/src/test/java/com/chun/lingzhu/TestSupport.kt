package com.chun.lingzhu

/**
 * 测试公用小工具。
 *
 * **别用正则写 emoji 字符类**：`[\uD83C-\uDBFF\uDC00-\uDFFF]` 看着像「代理区」，但 Java/Kotlin 的
 * 正则会把**相邻的一对代理转义**（\uDBFF\uDC00）合成一个增补码点，于是那个 `-` 变成
 * 「\uD83C … 增补码点」的超大区间，连 `：`（U+FF1A）都会被算成 emoji（实测踩过）。
 * 按 code point 判就没有这个坑。
 */
fun hasEmoji(s: String): Boolean = s.codePoints().anyMatch { cp ->
    cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F000..0x1FAFF || cp == 0xFE0F
}
