/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import java.nio.file.Paths

import com.hypertino.binders.value.{Null, Obj}
import com.hypertino.mediaupload.api.{Media, MediaStatus}
import com.hypertino.services.mediaupload.storage.FileStorageClient
import com.hypertino.services.mediaupload.transform.{ImageMediaTransformer, VideoMediaTransformer}
import org.scalatest.{FlatSpec, Matchers}

class VideoMediaTransformerSpec extends FlatSpec with Matchers {
  "ImageMediaTransformer" should "generate dimensions" in {
    val rootPath = "./src/test/resources/image-media-transformer"
    val storageClient = new FileStorageClient(rootPath)
    val w = Watermark(rootPath + "/watermark.png", None, None, Some(30), Some(30), Some(124), Some(124), percents = false)

    val transformation = new Transformation {
      def watermark: Option[Watermark] = Some(w)
      def dimensions: Seq[Dimensions] = Seq(
        Dimensions(Some(400), Some(300), Some(80)),
        Dimensions(Some(700), Some(600), Some(90)),
        Dimensions(Some(1440), Some(1080), Some(90))
      )
      def thumbnails: Seq[Dimensions] = Seq(
        Dimensions(Some(400), Some(300), Some(90)),
        Dimensions(Some(700), Some(600), Some(90)),
        Dimensions(Some(1440), Some(1080), Some(80))
      )
    }
    val tr = new VideoMediaTransformer(transformation, storageClient, "input")
    val r = tr.transform("3.mp4",Paths.get(rootPath + "/input/3.mp4"))
    r._1 shouldBe Obj.from(
      "400x300" -> "./src/test/resources/image-media-transformer/input/3-400x300.mp4",
      "700x600" -> "./src/test/resources/image-media-transformer/input/3-700x600.mp4",
      "1440x1080" -> "./src/test/resources/image-media-transformer/input/3-1440x1080.mp4"
    )
    r._2 shouldBe Obj.from(
      "400x300" -> "./src/test/resources/image-media-transformer/input/3.mp4-400x300.jpeg",
      "700x600" -> "./src/test/resources/image-media-transformer/input/3.mp4-700x600.jpeg",
      "1440x1080" -> "./src/test/resources/image-media-transformer/input/3.mp4-1440x1080.jpeg"
    )
  }
//
//  it should "generate dimensions ELBI" in {
//    val storageClient = new FileStorageClient("/Users/maqdev/Downloads")
//    val w = Watermark("/Users/maqdev/dev/elbi/backend/data/watermarks/elbi.png", None, None, Some(-1), Some(2), None, Some(7), percents = true)
//
//    val transformation = new Transformation {
//      def watermark: Option[Watermark] = Some(w)
//      def dimensions: Seq[Dimensions] = Seq(
//        Dimensions(Some(480), Some(848), Some(80))
//      )
//      def thumbnails: Seq[Dimensions] = Seq(
//        Dimensions(Some(480), Some(848), Some(80))
//      )
//    }
//    val tr = new VideoMediaTransformer(transformation, storageClient, "input")
//    val r = tr.transform("123.mov",Paths.get("/Users/maqdev/Downloads/123.mov"))
//    r._1 shouldBe Obj.from(
//      "480x848" -> "/Users/maqdev/Downloads/input/123-480x848.mov"
//    )
//    r._2 shouldBe Obj.from(
//      "480x848" -> "/Users/maqdev/Downloads/input/123.mov-480x848.jpeg"
//    )
//  }
}
