package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfSlideElement
import org.xmlpull.v1.XmlPullParser

/** PPTX group/connector parser (split from OoxmlPptx; behavior identical). */
internal object OoxmlPptxGroups {

    /** Affine map (per-axis scale + offset) from a group's child coordinate space to screen px@96. */
    internal class GroupTf(val ax: Float, val bx: Float, val ay: Float, val by: Float) {
        fun apply(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(ax * x + bx, ay * y + by, w * ax, h * ay)
        companion object {
            /** parent ∘ child: apply child first (its space -> parent's child space), then parent. */
            fun compose(p: GroupTf, c: GroupTf) =
                GroupTf(p.ax * c.ax, p.ax * c.bx + p.bx, p.ay * c.ay, p.ay * c.by + p.by)
        }
    }

    fun parseGroup(parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, parentTf: GroupTf?): List<OdfSlideElement> {
        val depth = parser.depth
        val out = mutableListOf<OdfSlideElement>()
        var tf = parentTf
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "grpSp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) tf = applyGroupTag(parser, ctx, tf, parentTf, out)
            e = parser.next()
        }
        return out
    }

    private fun applyGroupTag(
        parser: XmlPullParser, ctx: OoxmlPptx.SlideCtx, tf: GroupTf?, parentTf: GroupTf?,
        out: MutableList<OdfSlideElement>,
    ): GroupTf? {
        var cur = tf
        when (parser.name) {
            // grpSpPr precedes the children; fold the group's own off/ext/chOff/chExt into the transform.
            "grpSpPr" -> parseGroupXfrm(parser)?.let { own -> cur = parentTf?.let { GroupTf.compose(
                it,
                own) } ?: own }
            "sp" -> OoxmlPptx.parseShape(parser, ctx)?.let { out.add(OoxmlPptx.applyTf(it, cur)) }
            "pic" -> OoxmlPptx.parsePic(parser, ctx)?.let { out.add(OoxmlPptx.applyTf(it, cur)) }
            "cxnSp" -> OoxmlPptx.parseConnector(parser, ctx)?.let { out.add(OoxmlPptx.applyTf(it, cur)) }
            "grpSp" -> out.addAll(parseGroup(parser, ctx, cur))
        }
        return cur
    }

    private class GroupXfrmAcc(
        var offX: Float = 0f,
        var offY: Float = 0f,
        var extX: Float = 0f,
        var extY: Float = 0f,
        var chOffX: Float = 0f,
        var chOffY: Float = 0f,
        var chExtX: Float = 0f,
        var chExtY: Float = 0f,
        var inXfrm: Boolean = false,
        var seen: Boolean = false,
    ) {
        fun toTf(): GroupTf {
            val ax = if (chExtX != 0f) extX / chExtX else 1f
            val ay = if (chExtY != 0f) extY / chExtY else 1f
            return GroupTf(ax, offX - chOffX * ax, ay, offY - chOffY * ay)
        }
    }

    /** Reads a group's <a:xfrm> (off/ext/chOff/chExt) into a child-space -> parent-space transform. */
    private fun parseGroupXfrm(parser: XmlPullParser): GroupTf? {
        val depth = parser.depth
        val acc = GroupXfrmAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "grpSpPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyGroupXfrmTag(parser, acc)
            else if (e == XmlPullParser.END_TAG && parser.name == "xfrm") acc.inXfrm = false
            e = parser.next()
        }
        if (!acc.seen) return null
        return acc.toTf()
    }

    private fun applyGroupXfrmTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        when (parser.name) {
            "xfrm" -> { acc.inXfrm = true; acc.seen = true }
            "off" -> applyGroupOffTag(parser, acc)
            "ext" -> applyGroupExtTag(parser, acc)
            "chOff" -> applyGroupChOffTag(parser, acc)
            "chExt" -> applyGroupChExtTag(parser, acc)
        }
    }

    private fun groupEmu(parser: XmlPullParser, name: String): Float =
        OoxmlXml.attr(parser, name)?.toLongOrNull()?.let { OoxmlUnits.emuToPx(it) } ?: 0f

    private fun applyGroupOffTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.offX = groupEmu(parser, "x")
        acc.offY = groupEmu(parser, "y")
    }

    private fun applyGroupExtTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.extX = groupEmu(parser, "cx")
        acc.extY = groupEmu(parser, "cy")
    }

    private fun applyGroupChOffTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.chOffX = groupEmu(parser, "x")
        acc.chOffY = groupEmu(parser, "y")
    }

    private fun applyGroupChExtTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.chExtX = groupEmu(parser, "cx")
        acc.chExtY = groupEmu(parser, "cy")
    }
}
