package com.hypertino.services.mediaupload

import java.io.{ByteArrayInputStream, InputStream, OutputStream}

import akka.actor.{Actor, ActorSystem, Props}
import akka.io.IO
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.InternalServerError
import com.hypertino.hyperbus.util.SeqGenerator
import com.hypertino.service.control.api.Service
import com.hypertino.services.mediaupload.impl.UploadProxyActor
import com.typesafe.config.Config
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

case class MediaUploadProxyServiceConfiguration(s3: S3Config, port: Int,
                                                interface: String = "::0",
                                                processingTimeout: FiniteDuration = 1.minute)

class MediaUploadProxyService(implicit val injector: Injector) extends Service with Injectable {
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  protected implicit val actorSystem = ActorSystem()
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MediaUploadProxyServiceConfiguration]("media-upload-proxy")
  protected val minioClient = new MinioClient(config.s3.endpoint, config.s3.accessKey, config.s3.secretKey)

  // the handler actor replies to incoming HttpRequests
  protected val proxyHttpHandler = actorSystem.actorOf(Props(new UploadProxyActor(hyperbus,minioClient, config.processingTimeout)), name = "proxy-handler" + SeqGenerator.create())
  IO(Http) ! Http.Bind(proxyHttpHandler, interface = config.interface, port = config.port)

  log.info("MediaUploadProxyService started")

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    log.info("MediaUploadProxyService stopped")
  }
}
