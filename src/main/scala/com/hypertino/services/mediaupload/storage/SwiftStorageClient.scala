/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.storage

import java.io.{File, InputStream}
import java.net.{URI, URLDecoder}

import com.typesafe.config.Config
import scaldi.{Injectable, Injector}

case class SwiftStorageClientConfig(authUrl: String, username: String, password: String, tenantName: Option[String], tenantId: Option[String], storageUriBase: Option[String])

class SwiftStorageClient(implicit val injector: Injector) extends StorageClient with Injectable {
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[SwiftStorageClientConfig]("swift")

  val swiftAccountFactory = new org.javaswift.joss.client.factory.AccountFactory()
    .setUsername(config.username)
    .setPassword(config.password)
    .setAuthUrl(config.authUrl)
  config.tenantId.foreach(swiftAccountFactory.setTenantId)
  config.tenantName.foreach(swiftAccountFactory.setTenantName)

  val swiftAccount = swiftAccountFactory.createAccount()

  override def upload(bucket: String, path: String, stream: InputStream, contentType: Option[String]): String = {
    val container = swiftAccount.getContainer(bucket)

    val obj = container.getObject(path)
    obj.uploadObject(stream)
    contentType.foreach(obj.setContentType)

    config.storageUriBase.map { o ⇒
      o + "/" + bucket + "/" + path
    } getOrElse {
      // we do this because our specific swift encodes / in URL
      // maybe it's version / configuration depndent
      val url = new URI(obj.getPublicURL)
      val decodedPath = URLDecoder.decode(url.getPath, "UTF-8")
      url.getScheme + "://" + url.getAuthority + decodedPath
    }
  }

  override def download(bucket: String, path: String, localFileName: String): Unit = {
    val container = swiftAccount.getContainer(bucket)
    val obj = container.getObject(path)
    obj.downloadObject(new File(localFileName))
  }
}
