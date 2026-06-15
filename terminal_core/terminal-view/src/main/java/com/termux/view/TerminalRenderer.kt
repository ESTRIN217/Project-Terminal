package com.termux.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

class TerminalRenderer(val mTextSize: Int, val mTypeface: Typeface) {

    private val mTextPaint = Paint()

    val mFontWidth: Float
    val mFontLineSpacing: Int
    private val mFontAscent: Int
    val mFontLineSpacingAndAscent: Int

    private val asciiMeasures = FloatArray(127)

    init {
        mTextPaint.typeface = mTypeface
        mTextPaint.isAntiAlias = true
        mTextPaint.textSize = mTextSize.toFloat()

        mFontLineSpacing = Math.ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = Math.ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent
        mFontWidth = mTextPaint.measureText("X")

        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }
    }

    fun render(
        mEmulator: com.termux.terminal.TerminalEmulator,
        canvas: Canvas,
        topRow: Int,
        selectionY1: Int,
        selectionY2: Int,
        selectionX1: Int,
        selectionX2: Int
    ) {
        val reverseVideo = mEmulator.isReverseVideo
        val endRow = topRow + mEmulator.mRows
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.cursorCol
        val cursorRow = mEmulator.cursorRow
        val cursorVisible = mEmulator.shouldCursorBeVisible()
        val screen = mEmulator.screen
        val palette = mEmulator.mColors.mCurrentColors
        val cursorShape = mEmulator.cursorStyle

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC)

        var heightOffset = mFontLineSpacingAndAscent.toFloat()
        for (row in topRow until endRow) {
            heightOffset += mFontLineSpacing

            val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
            var selx1 = -1
            var selx2 = -1
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1
                selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
            }

            val lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val line = lineObject.mText
            val charsUsedInLine = lineObject.spaceUsed

            var lastRunStyle = 0L
            var lastRunInsideCursor = false
            var lastRunInsideSelection = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0
            var measuredWidthForRun = 0f

            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighsurrogate = Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighsurrogate) 2 else 1
                val codePoint = if (charIsHighsurrogate) Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) else charAtIndex.code
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor = cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1)
                val insideSelection = column >= selx1 && column <= selx2
                val style = lineObject.getStyle(column)

                val measuredCodePointWidth = if (codePoint < asciiMeasures.size) {
                    asciiMeasures[codePoint]
                } else {
                    mTextPaint.measureText(line, currentCharIndex, charsForCodePoint)
                }
                val fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch
                ) {
                    if (column != 0) {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
                        var invertCursorTextColor = false
                        if (lastRunInsideCursor && cursorShape == com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true
                        }
                        drawTextRun(
                            canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunStyle = style
                    lastRunInsideCursor = insideCursor
                    lastRunInsideSelection = insideSelection
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    currentCharIndex += if (Character.isHighSurrogate(line[currentCharIndex])) 2 else 1
                }
            }

            val columnWidthSinceLastRun = columns - lastRunStartColumn
            val charsSinceLastRun = currentCharIndex - lastRunStartIndex
            val cursorColor = if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
            var invertCursorTextColor = false
            if (lastRunInsideCursor && cursorShape == com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true
            }
            drawTextRun(
                canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection
            )
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        palette: IntArray,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        mes: Float,
        cursor: Int,
        cursorStyle: Int,
        textStyle: Long,
        reverseVideo: Boolean
    ) {
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        val underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        val italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0
        val strikeThrough = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        val dim = effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0

        if (foreColor and 0xff000000.toInt() != 0xff000000.toInt()) {
            if (bold && foreColor in 0..7) foreColor += 8
            foreColor = palette[foreColor]
        }

        if (backColor and 0xff000000.toInt() != 0xff000000.toInt()) {
            backColor = palette[backColor]
        }

        val reverseVideoHere = reverseVideo xor (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
        var fg = foreColor
        var bg = backColor
        if (reverseVideoHere) {
            val tmp = fg
            fg = bg
            bg = tmp
        }

        var left = startColumn * mFontWidth
        var right = left + runWidthColumns * mFontWidth

        var measured = mes / mFontWidth
        var savedMatrix = false
        if (Math.abs(measured - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / measured, 1f)
            left *= measured / runWidthColumns
            right *= measured / runWidthColumns
            savedMatrix = true
        }

        if (bg != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            mTextPaint.color = bg
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }

        if (cursor != 0) {
            mTextPaint.color = cursor
            var cursorHeight = mFontLineSpacingAndAscent - mFontAscent
            when (cursorStyle) {
                com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE -> cursorHeight = (cursorHeight / 4f).toInt()
                com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR -> right -= (right - left) * 3 / 4f
            }
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        }

        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
            if (dim) {
                val red = (0xFF and (fg shr 16)) * 2 / 3
                val green = (0xFF and (fg shr 8)) * 2 / 3
                val blue = (0xFF and fg) * 2 / 3
                fg = -0x1000000 + (red shl 16) + (green shl 8) + blue
            }

            mTextPaint.isFakeBoldText = bold
            mTextPaint.isUnderlineText = underline
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = fg

            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint)
        }

        if (savedMatrix) canvas.restore()
    }

    fun getFontWidth(): Float = mFontWidth

    fun getFontLineSpacing(): Int = mFontLineSpacing
}
