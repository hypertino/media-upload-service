/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.impl

object DimensionsUtil {
  def getNewDimensions(x: Int, y: Int, toX: Option[Int], toY: Option[Int]): (Int,Int) = {
    (toX, toY) match {
      case (Some(nx), Some(ny)) ⇒
        (nx, ny)

      case (None, None) ⇒
        (x, y)

      case (Some(nx), None) ⇒
        val d = x.toDouble / nx.toDouble
        (nx, Math.round(y / d).toInt)

      case (None, Some(ny)) ⇒
        val d = y.toDouble / ny.toDouble
        (Math.round(x / d).toInt, ny)
    }
  }
}
