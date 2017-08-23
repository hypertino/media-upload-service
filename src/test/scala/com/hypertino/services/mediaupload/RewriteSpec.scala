package com.hypertino.services.mediaupload

import org.scalatest.{FlatSpec, Matchers}

class RewriteSpec extends FlatSpec with Matchers {
  "Rewrite" should "do plain rewrite" in {
    val rewrite = Rewrite("(.*)//s3.int.example.com/(.*)", "https://cdn.example.com/$2")
    rewrite.rewrite("https://s3.int.example.com/abcde/123.jpeg") shouldBe Some("https://cdn.example.com/abcde/123.jpeg")
    rewrite.rewrite("https://s3.int.other.com/abcde/123.jpeg") shouldBe None
  }
}
