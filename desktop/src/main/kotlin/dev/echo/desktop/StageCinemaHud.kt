package dev.echo.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Transparent owned HUD; the VLC Canvas remains the only video and audio player. */
internal class StageCinemaHud(
    private val owner: Window,
    private val onInteraction: () -> Unit,
) : AutoCloseable {
    private val panel = HudPanel()
    val window: JWindow = JWindow(owner).apply {
        type = Window.Type.UTILITY
        setFocusableWindowState(false)
        background = Color(0, 0, 0, 0)
        contentPane = panel
        bounds = owner.bounds
    }
    private val ownerListener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = syncBounds()
        override fun componentResized(event: ComponentEvent) = syncBounds()
    }
    private val mouseListener = object : java.awt.event.MouseAdapter() {
        override fun mousePressed(event: java.awt.event.MouseEvent) = onInteraction()
        override fun mouseMoved(event: java.awt.event.MouseEvent) = onInteraction()
    }
    private val clock = Timer(500) { panel.repaint() }

    init {
        panel.addMouseListener(mouseListener)
        panel.addMouseMotionListener(mouseListener)
        owner.addComponentListener(ownerListener)
    }

    fun show(title: String?, artist: String?, positionMs: Long, durationMs: Long, playing: Boolean) = onEdt {
        panel.update(title, artist, positionMs, durationMs, playing)
        syncBounds()
        if (!window.isVisible) window.isVisible = true
        window.toFront()
        clock.start()
    }

    fun update(title: String?, artist: String?, positionMs: Long, durationMs: Long, playing: Boolean) = onEdt {
        panel.update(title, artist, positionMs, durationMs, playing)
    }

    fun owns(source: Window?): Boolean = source === window

    override fun close() = onEdt {
        clock.stop()
        owner.removeComponentListener(ownerListener)
        panel.removeMouseListener(mouseListener)
        panel.removeMouseMotionListener(mouseListener)
        window.isVisible = false
        window.dispose()
    }

    private fun syncBounds() {
        if (owner.isShowing) window.bounds = owner.bounds
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private class HudPanel : JComponent() {
        private var title: String = "Echo"
        private var artist: String = ""
        private var positionMs: Long = 0
        private var durationMs: Long = 0
        private var playing: Boolean = false
        private var sampledAtNanos: Long = System.nanoTime()

        init { isOpaque = false }

        fun update(title: String?, artist: String?, positionMs: Long, durationMs: Long, playing: Boolean) {
            this.title = title.orEmpty().ifBlank { "Echo" }
            this.artist = artist.orEmpty()
            this.positionMs = positionMs.coerceAtLeast(0)
            this.durationMs = durationMs.coerceAtLeast(0)
            this.playing = playing
            sampledAtNanos = System.nanoTime()
            repaint()
        }

        override fun paintComponent(raw: Graphics) {
            val g = raw.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                val scale = (w / 1440f).coerceIn(.72f, 1.5f)
                val marginX = (w * .055f).coerceIn(36f, 96f).toInt()
                val top = (h * .065f).coerceIn(34f, 76f).toInt()
                val bottom = (h * .075f).coerceIn(38f, 90f).toInt()

                g.paint = GradientPaint(0f, 0f, Color(0, 0, 0, 118), 0f, (h * .26f), Color(0, 0, 0, 0))
                g.fillRect(0, 0, w, (h * .28f).toInt())
                g.paint = GradientPaint(0f, (h * .47f), Color(0, 0, 0, 0), 0f, h.toFloat(), Color(0, 0, 0, 220))
                g.fillRect(0, (h * .47f).toInt(), w, (h * .53f).toInt())

                val markSize = (28 * scale).toInt().coerceAtLeast(20)
                val markX = marginX + markSize / 2
                val markY = top + markSize / 2
                val markSpacing = markSize / 4
                g.color = Color(0xB9A1FF)
                g.stroke = BasicStroke(3.5f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(markX - markSpacing, markY - markSize / 7, markX - markSpacing, markY + markSize / 7)
                g.drawLine(markX, markY - markSize / 3, markX, markY + markSize / 3)
                g.drawLine(markX + markSpacing, markY - markSize / 5, markX + markSpacing, markY + markSize / 5)
                val titleX = marginX + markSize + (12 * scale).toInt()
                g.color = Color.WHITE
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (25 * scale).toInt().coerceAtLeast(18))
                g.drawString("Echo", titleX, top + markSize - 2)

                val current = (positionMs + if (playing) (System.nanoTime() - sampledAtNanos).coerceAtLeast(0) / 1_000_000 else 0)
                    .coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                val textX = marginX
                val titleY = h - bottom - (76 * scale).toInt()
                g.color = Color.WHITE
                g.font = Font(Font.SANS_SERIF, Font.BOLD, (34 * scale).toInt().coerceAtLeast(24))
                g.drawString(ellipsize(g, title, (w * .66f).toInt()), textX, titleY)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, (19 * scale).toInt().coerceAtLeast(14))
                g.color = Color(255, 255, 255, 190)
                if (artist.isNotBlank()) g.drawString(ellipsize(g, artist, (w * .66f).toInt()), textX, titleY + (30 * scale).toInt())

                val barY = h - bottom
                val barW = stageHudBarWidth(w, marginX)
                val barH = (3 * scale).toInt().coerceAtLeast(2)
                g.color = Color(255, 255, 255, 92)
                g.fillRoundRect(textX, barY, barW, barH, barH, barH)
                val progress = if (durationMs > 0) (current.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
                g.color = Color.WHITE
                g.fillRoundRect(textX, barY, (barW * progress).toInt(), barH, barH, barH)
                val timeY = barY + (27 * scale).toInt()
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, (13 * scale).toInt().coerceAtLeast(11))
                g.color = Color(255, 255, 255, 210)
                g.drawString(stageHudClock(current), textX, timeY)
                val total = stageHudClock(durationMs)
                g.drawString(total, textX + barW - g.fontMetrics.stringWidth(total), timeY)
            } finally { g.dispose() }
        }

        private fun ellipsize(g: Graphics2D, value: String, maxWidth: Int): String {
            if (g.fontMetrics.stringWidth(value) <= maxWidth) return value
            var end = value.length
            while (end > 1 && g.fontMetrics.stringWidth(value.substring(0, end) + "…") > maxWidth) end--
            return value.substring(0, end) + "…"
        }
    }
}

private fun stageHudClock(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1_000 % 60)

internal fun stageHudBarWidth(windowWidth: Int, marginX: Int): Int =
    (windowWidth * .48f).toInt().coerceIn(1, (windowWidth - marginX * 2).coerceAtLeast(1))
