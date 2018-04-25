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
import com.hypertino.services.mediaupload.transform.ImageMediaTransformer
import org.scalatest.{FlatSpec, Matchers}

class ImageMediaTransformerSpec extends FlatSpec with Matchers {
  "ImageMediaTransformer" should "generate dimensions" in {
    val rootPath = "./src/test/resources/image-media-transformer"
    val storageClient = new FileStorageClient(rootPath)

    val watermark = Watermark(rootPath + "/watermark.png", None, None, Some(5), Some(5), percents = true)

    val transformation = Transformation(
      Some(watermark),
      Seq(
        Dimensions(Some(400), Some(300), Some(10)),
        Dimensions(Some(700), Some(600), Some(9))
      )
    )
    val imageMediaTransformer = new ImageMediaTransformer(transformation, storageClient, "input")
    val r = imageMediaTransformer.transform(Media("1", "1.jpg",Null,MediaStatus.SCHEDULED),"1.jpg",Paths.get(rootPath + "/input/1.jpg"))
    r shouldBe Obj.from(
      "400x300" -> "./src/test/resources/image-media-transformer/input/1-400x300.jpg",
      "700x600" -> "./src/test/resources/image-media-transformer/input/1-700x600.jpg"
    )

    val r2 = imageMediaTransformer.transform(Media("1", "2.png",Null,MediaStatus.SCHEDULED),"2.png",Paths.get(rootPath + "/input/2.png"))
    r2 shouldBe Obj.from(
      "400x300" -> "./src/test/resources/image-media-transformer/input/2-400x300.png",
      "700x600" -> "./src/test/resources/image-media-transformer/input/2-700x600.png"
    )
  }
}
