/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.storage

import java.io.InputStream

import com.typesafe.config.Config
import io.minio.MinioClient
import scaldi.{Injectable, Injector}

case class MinioStorageClientConfig(endpoint: String, accessKey: String, secretKey: String)

class MinioStorageClient(implicit val injector: Injector) extends StorageClient with Injectable {
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MinioStorageClientConfig]("s3")
  protected val minioClient = new MinioClient(config.endpoint, config.accessKey, config.secretKey)

  override def upload(bucket: String, path: String, stream: InputStream, contentType: Option[String]): String = {
    minioClient.putObject(bucket, path, stream, contentType.orNull)
    config.endpoint + "/" + bucket + "/" + path
  }
  override def download(bucket: String, path: String, localFileName: String): Unit = {
    minioClient.getObject(bucket, path, localFileName)
  }
}
