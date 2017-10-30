/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import java.io.{ByteArrayInputStream, InputStream, OutputStream}

import akka.actor.{Actor, ActorSystem, Props}
import akka.io.IO
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.InternalServerError
import com.hypertino.hyperbus.util.SeqGenerator
import com.hypertino.service.control.api.Service
import com.hypertino.services.mediaupload.impl.UploadProxyActor
import com.hypertino.services.mediaupload.storage.StorageClient
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import io.minio.MinioClient
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
import scaldi.{Injectable, Injector}
import spray.can.Http
import spray.http._
import spray.http.MediaTypes._
import spray.routing._

import scala.concurrent.duration._
import scala.concurrent.Future

case class MediaUploadProxyServiceConfiguration(port: Int,
                                                defaultBucketName: String,
                                                interface: String = "::0",
                                                directTransform: Boolean = false,
                                                processingTimeout: FiniteDuration = 1.minute)

class MediaUploadProxyService(implicit val injector: Injector) extends Service with Injectable with StrictLogging {
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  protected implicit val actorSystem = ActorSystem()
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MediaUploadProxyServiceConfiguration]("media-upload-proxy")
  protected val storageClient = inject[StorageClient] (identified by 'mediaStorageClient)

  // the handler actor replies to incoming HttpRequests
  protected val proxyHttpHandler = actorSystem.actorOf(Props(new UploadProxyActor(hyperbus, storageClient, config.directTransform, config.processingTimeout, config.defaultBucketName)), name = "proxy-handler" + SeqGenerator.create())
  IO(Http) ! Http.Bind(proxyHttpHandler, interface = config.interface, port = config.port)

  logger.info(s"${getClass.getName} is STARTED")

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = actorSystem.terminate().map { _ ⇒
    logger.info(s"${getClass.getName} is STOPPED")
  }
}
