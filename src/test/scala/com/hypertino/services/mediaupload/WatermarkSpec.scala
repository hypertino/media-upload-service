/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import org.scalatest.{FlatSpec, Matchers}

class WatermarkSpec extends FlatSpec with Matchers {
  "Watermark" should "place in absolute pixels (right-bottom)" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), percents = false)
    watermark.placement(10, 8, 200, 200) shouldBe (185,187)
  }

  it should "place in absolute pixels (left-top)" in {
    val watermark = Watermark("1.png", Some(5), Some(5), None, None, percents = false)
    watermark.placement(10, 8, 200, 200) shouldBe (5,5)
  }

  it should "place in percents (right-bottom)" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (180,182)
  }

  it should "place in percents (left-top)" in {
    val watermark = Watermark("1.png", Some(5), Some(5), None, None, percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (10,10)
  }
}
