/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import com.hypertino.services.mediaupload.impl.DimensionsUtil
import org.scalatest.{FlatSpec, Matchers}

class DimensionUtilsSpec extends FlatSpec with Matchers {
  "DimensionsUtil" should "getNewDimensions by one dimension" in {
    DimensionsUtil.getNewDimensions(10, 20, Some(5), None) shouldBe (5, 10)
    DimensionsUtil.getNewDimensions(20, 10, None, Some(5)) shouldBe (10, 5)
  }
}
