/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ui

import android.graphics.Canvas
import android.graphics.Paint
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * Line renderer used by the memory usage chart.
 *
 * An entry with a `NaN` y-value represents a slot for which no sample could be collected. The
 * built-in [LineChartRenderer] has no dependable way of showing such gaps, so this renderer draws
 * only the segments whose two endpoints hold valid values and leaves the missing slots blank.
 */
class MemoryUsageLineChartRenderer(
    chart: LineDataProvider,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler,
) : LineChartRenderer(chart, animator, viewPortHandler) {

  private val segment = FloatArray(4)

  override fun drawLinear(c: Canvas, dataSet: ILineDataSet) {
    val entryCount = dataSet.entryCount
    if (entryCount < 2) {
      return
    }

    val transformer = mChart.getTransformer(dataSet.axisDependency) ?: return
    val phaseY = mAnimator.phaseY
    val canvas = if (dataSet.isDashedLineEnabled) mBitmapCanvas else c

    mRenderPaint.style = Paint.Style.STROKE
    mRenderPaint.color = dataSet.getColor()

    for (index in 0 until entryCount - 1) {
      val start = dataSet.getEntryForIndex(index) ?: continue
      val end = dataSet.getEntryForIndex(index + 1) ?: continue

      val startY = start.y
      val endY = end.y
      if (startY.isNaN() || endY.isNaN()) {
        continue
      }

      segment[0] = start.x
      segment[1] = startY * phaseY
      segment[2] = end.x
      segment[3] = endY * phaseY

      transformer.pointValuesToPixel(segment)
      canvas.drawLines(segment, 0, segment.size, mRenderPaint)
    }
  }
}
