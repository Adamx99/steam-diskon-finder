package com.example.steamdiskon

import java.io.OutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Gambar cover untuk disematkan di Excel. ext: "jpeg" atau "png". */
class XlsxImage(val bytes: ByteArray, val ext: String, val w: Int, val h: Int)

/** Penulis .xlsx minimal tanpa library tambahan (teks, angka, tanggal, warna, link, cover). */
object XlsxWriter {
    private const val COLS = "ABCDEFGHIJ"
    private val HEADERS = listOf(
        "No", "Cover", "Game", "Rating", "Ulasan", "Diskon (%)",
        "Harga normal", "Harga diskon", "Berakhir", "Link Steam",
    )

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 32 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun sCell(ref: String, text: String, style: Int): String =
        "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"

    private fun nCell(ref: String, num: String, style: Int): String =
        "<c r=\"$ref\" s=\"$style\"><v>$num</v></c>"

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        putBytes(zip, name, text.toByteArray(Charsets.UTF_8))
    }

    fun write(out: OutputStream, rows: List<Game>, images: Map<String, XlsxImage>) {
        val nf = DecimalFormat("0.########", DecimalFormatSymbols(Locale.US))
        val n = rows.size
        val sb = StringBuilder()

        sb.append("<row r=\"1\" ht=\"22\" customHeight=\"1\">")
        HEADERS.forEachIndexed { i, h -> sb.append(sCell("${COLS[i]}1", h, 1)) }
        sb.append("</row>")

        val imgRows = ArrayList<Pair<Int, String>>()
        rows.forEachIndexed { i, g ->
            val r = i + 2
            val pStyle = if (regionOf(g.cc).dec > 0) 10 else 9
            val img = images[g.appid]
            if (img != null) imgRows.add(Pair(r, g.appid))
            sb.append("<row r=\"$r\"")
            if (img != null) {
                val dh = maxOf(Math.round(img.h * 120.0 / img.w).toInt(), 1)
                sb.append(" ht=\"${nf.format(dh * 0.75 + 6)}\" customHeight=\"1\"")
            }
            sb.append(">")
            sb.append(nCell("A$r", (i + 1).toString(), 3))
            sb.append(sCell("C$r", g.name, 2))
            sb.append(sCell("D$r", starsText(g.pct), if (g.pct != null) 4 else 3))
            val rs = when (reviewClass(g)) {
                "positive" -> 5
                "negative" -> 6
                "mixed" -> 7
                else -> 3
            }
            sb.append(sCell("E$r", reviewText(g), rs))
            sb.append(nCell("F$r", nf.format(g.disc / 100.0), 8))
            sb.append(nCell("G$r", nf.format(g.initial), pStyle))
            sb.append(nCell("H$r", nf.format(g.finalPrice), pStyle))
            val e = g.end
            if (e != null) {
                val off = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(e)).totalSeconds
                sb.append(nCell("I$r", nf.format((e + off) / 86400.0 + 25569.0), 11))
            } else {
                sb.append(sCell("I$r", "-", 3))
            }
            val url = "https://store.steampowered.com/app/${g.appid}/"
            val formula = esc("HYPERLINK(\"$url\",\"$url\")")
            sb.append("<c r=\"J$r\" s=\"12\" t=\"str\"><f>$formula</f><v>${esc(url)}</v></c>")
            sb.append("</row>")
        }

        val hasImages = imgRows.isNotEmpty()
        val sheet = XlsxParts.SHEET_PREFIX + sb +
            "</sheetData><autoFilter ref=\"A1:J${n + 1}\"/>" +
            (if (hasImages) "<drawing r:id=\"rId1\"/>" else "") +
            "</worksheet>"

        val zip = ZipOutputStream(out)
        putText(zip, "[Content_Types].xml", if (hasImages) XlsxParts.CT_WITH else XlsxParts.CT_WITHOUT)
        putText(zip, "_rels/.rels", XlsxParts.ROOT_RELS)
        putText(zip, "xl/workbook.xml", XlsxParts.WB_PREFIX + (n + 1) + XlsxParts.WB_SUFFIX)
        putText(zip, "xl/_rels/workbook.xml.rels", XlsxParts.WB_RELS)
        putText(zip, "xl/styles.xml", XlsxParts.STYLES)
        putText(zip, "xl/worksheets/sheet1.xml", sheet)

        if (hasImages) {
            putText(zip, "xl/worksheets/_rels/sheet1.xml.rels", XlsxParts.SHEET_RELS)
            val anchors = StringBuilder()
            val rels = StringBuilder()
            imgRows.forEachIndexed { idx, pair ->
                val k = idx + 1
                val img = images.getValue(pair.second)
                val dw = 120
                val dh = maxOf(Math.round(img.h * 120.0 / img.w).toInt(), 1)
                putBytes(zip, "xl/media/image$k.${img.ext}", img.bytes)
                anchors.append(
                    XlsxParts.ANCHOR
                        .replace("@ROW@", (pair.first - 1).toString())
                        .replace("@CX@", (dw * 9525).toString())
                        .replace("@CY@", (dh * 9525).toString())
                        .replace("@ID@", (k + 1).toString())
                        .replace("@K@", k.toString())
                )
                rels.append(XlsxParts.REL.replace("@K@", k.toString()).replace("@EXT@", img.ext))
            }
            putText(zip, "xl/drawings/drawing1.xml", XlsxParts.DRAWING_HEAD + anchors + XlsxParts.DRAWING_TAIL)
            putText(zip, "xl/drawings/_rels/drawing1.xml.rels", XlsxParts.DRAWING_RELS_HEAD + rels + XlsxParts.DRAWING_RELS_TAIL)
        }
        zip.finish()
        zip.flush()
    }
}
