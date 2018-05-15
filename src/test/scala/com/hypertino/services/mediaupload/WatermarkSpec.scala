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
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), None, None, percents = false)
    watermark.placement(10, 8, 200, 200) shouldBe (185,187, 10, 8)
  }

  it should "place in absolute pixels (left-top)" in {
    val watermark = Watermark("1.png", Some(5), Some(5), None, None, None, None, percents = false)
    watermark.placement(10, 8, 200, 200) shouldBe (5,5,10,8)
  }

  it should "place in absolute pixels (left-top) symmetrical" in {
    val watermark = Watermark("1.png", Some(5), Some(-1), None, None, None, None, percents = false)
    watermark.placement(10, 8, 200, 100) shouldBe (5,5,10,8)
  }

  it should "place in percents (right-bottom)" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), None, None, percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (180,182,10,8)
  }

  it should "place in percents (right-bottom) symmetrical" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(-1), None, None, percents = true)
    watermark.placement(10, 8, 200, 100) shouldBe (180,82,10,8)
  }

  it should "place in percents (right-bottom) and scale according to width" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), Some(10), None, percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (170,174,20,16)
  }

  it should "place in percents (right-bottom) and scale according to height" in {
    val watermark = Watermark("1.png", None, None, Some(5), Some(5), None, Some(5), percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (178,180,12,10)
  }

  it should "place in percents (left-top)" in {
    val watermark = Watermark("1.png", Some(5), Some(5), None, None, None, None, percents = true)
    watermark.placement(10, 8, 200, 200) shouldBe (10,10,10,8)
  }
}
