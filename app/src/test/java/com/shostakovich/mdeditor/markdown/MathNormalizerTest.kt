package com.shostakovich.mdeditor.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MathNormalizer.normalizeInlineMath] のユニットテスト。
 * Obsidian の `$...$` → Markwon の `$$...$$` 変換と、変換してはいけないケースの両方を検証。
 */
class MathNormalizerTest {

    private fun norm(s: String) = MathNormalizer.normalizeInlineMath(s)

    @Test
    fun `インライン1個を二重ダラーに変換`() {
        assertEquals("粘度 \$\$\\eta\$\$ を計算", norm("粘度 \$\\eta\$ を計算"))
    }

    @Test
    fun `インライン複数を個別に変換`() {
        assertEquals("\$\$a\$\$ と \$\$b\$\$", norm("\$a\$ と \$b\$"))
    }

    @Test
    fun `数式記号を含むインライン`() {
        assertEquals("\$\$E=mc^2\$\$", norm("\$E=mc^2\$"))
    }

    @Test
    fun `ブロック数式は変換しない`() {
        val block = "\$\$\\int_0^1 x^2\\,dx\$\$"
        assertEquals(block, norm(block))
    }

    @Test
    fun `複数行ブロック数式は変換しない`() {
        val block = "前\n\$\$\na^2 + b^2\n\$\$\n後"
        assertEquals(block, norm(block))
    }

    @Test
    fun `通貨表記は変換しない`() {
        // 開き直後が数字でもペアの閉じ前が空白なので数式扱いしない
        val text = "価格は \$100 から \$200 まで"
        assertEquals(text, norm(text))
    }

    @Test
    fun `インラインコード内のドルは保護`() {
        val text = "コマンドは `echo \$PATH` を使う"
        assertEquals(text, norm(text))
    }

    @Test
    fun `フェンスドコード内のドルは保護`() {
        val text = "```sh\nVAR=\$x\necho \$VAR\n```"
        assertEquals(text, norm(text))
    }

    @Test
    fun `エスケープされたドルは変換しない`() {
        val text = "費用は \\\$5 です"
        assertEquals(text, norm(text))
    }

    @Test
    fun `ドルを含まない文は素通り`() {
        val text = "ただの段落。記号なし。"
        assertEquals(text, norm(text))
    }

    @Test
    fun `ブロックとインラインの混在`() {
        val input = "係数 \$k\$ について\n\$\$y = kx\$\$\nここで \$x\$ は変数"
        val expected = "係数 \$\$k\$\$ について\n\$\$y = kx\$\$\nここで \$\$x\$\$ は変数"
        assertEquals(expected, norm(input))
    }
}
