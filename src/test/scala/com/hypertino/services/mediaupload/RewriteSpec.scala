/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import org.scalatest.{FlatSpec, Matchers}

class RewriteSpec extends FlatSpec with Matchers {
  "Rewrite" should "do plain rewrite" in {
    val rewrite = Rewrite("(.*)//s3.int.example.com/(.*)", "https://cdn.example.com/$2")
    rewrite.rewrite("https://s3.int.example.com/abcde/123.jpeg") shouldBe Some("https://cdn.example.com/abcde/123.jpeg")
    rewrite.rewrite("https://s3.int.other.com/abcde/123.jpeg") shouldBe None
  }
}
