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
    val watermark = Watermark(rootPath + "/watermark.png", None, None, Some(50), Some(50), percents = true)

    val transformation = Transformation(
      Some(watermark),
      Seq(
        Dimensions(Some(400), Some(300), Some(20)),
        Dimensions(Some(700), Some(600), Some(9)),
        Dimensions(Some(1440), Some(1080), Some(9))
      )
    )
    val tr = new VideoMediaTransformer(transformation, storageClient, "input")
    val r = tr.transform(Media("1", "3.mp4",Null,MediaStatus.SCHEDULED),"3.mp4",Paths.get(rootPath + "/input/3.mp4"))
    r shouldBe Obj.from(
      "400x300" -> "./src/test/resources/image-media-transformer/input/3-400x300.mp4",
      "700x600" -> "./src/test/resources/image-media-transformer/input/3-700x600.mp4",
      "1440x1080" -> "./src/test/resources/image-media-transformer/input/3-1440x1080.mp4"
    )
  }
}
