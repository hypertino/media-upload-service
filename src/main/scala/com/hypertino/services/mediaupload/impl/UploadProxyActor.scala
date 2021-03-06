/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.impl

import akka.actor._
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{ErrorBody, Ok}
import com.hypertino.hyperbus.util.IdGenerator
import com.hypertino.mediaupload.api._
import com.hypertino.services.mediaupload.storage.StorageClient
import com.hypertino.services.mediaupload.utils.ErrorCode
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import spray.can.Http
import spray.can.Http.RegisterChunkHandler
import spray.http.HttpMethods._
import spray.http.MediaTypes._
import spray.http._

import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

class UploadProxyActor(hyperbus: Hyperbus,
                       storageApi: StorageClient,
                       directTransform: Boolean,
                       processingTimeout: FiniteDuration,
                       defaultBucketName: String) extends Actor with StrictLogging { // ExecutionContext for the futures and scheduler

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      sender ! index

    case r@HttpRequest(POST, uri, headers, entity: HttpEntity.NonEmpty, protocol) =>
      // emulate chunked behavior for POST requests to this path
      val parts = r.asPartStream()
      val client = sender
      val handler = context.actorOf(Props(new FileUploadHandler(client, parts.head.asInstanceOf[ChunkedRequestStart], uri, hyperbus, storageApi, directTransform, processingTimeout, defaultBucketName)))
      parts.tail.foreach(handler !)

    case s@ChunkedRequestStart(HttpRequest(POST, uri, _, _, _)) =>
      val client = sender
      val handler = context.actorOf(Props(new FileUploadHandler(client, s, uri, hyperbus, storageApi, directTransform, processingTimeout, defaultBucketName)))
      sender ! RegisterChunkHandler(handler)

    case _: HttpRequest => sender ! HttpResponse(status = 404, entity = "Unknown resource!")

    case Timedout(HttpRequest(method, uri, _, _, _)) =>
      sender ! HttpResponse(
        status = StatusCodes.GatewayTimeout,
        entity = "The " + method + " request to '" + uri + "' has timed out... (UploadProxy)"
      )
  }

  lazy val index = HttpResponse(
    entity = HttpEntity(`text/html`,
      <html>
        <body>
          <h1>Upload Proxy</h1>
          <p>It works!</p>
          <p>Test file upload:</p>
          <form action ="/" enctype="multipart/form-data" method="post">
            <input type="file" name="datafile" multiple=""></input>
            <br/>
            <input type="submit">Submit</input>
          </form>
        </body>
      </html>.toString()
    )
  )
}

import java.io.{File, FileInputStream, FileOutputStream}

import akka.actor._
import org.jvnet.mimepull.{MIMEMessage, MIMEPart}
import spray.http.HttpHeaders.{RawHeader, _}
import spray.http.MediaTypes._
import spray.http._
import spray.http.parser.HttpParser
import spray.io.CommandWrapper

import scala.concurrent.duration._

case class WatchMedia(mediaIdMap: Map[String,String], ttl: Long)

class FileUploadHandler(client: ActorRef,
                        start: ChunkedRequestStart,
                        uri: Uri,
                        hyperbus: Hyperbus,
                        storageClient: StorageClient,
                        directTransform: Boolean,
                        processingTimeout: FiniteDuration,
                        defaultBucketName: String
                       ) extends Actor with StrictLogging {

  // todo: exponentialCheck
  val checkProcessingEach = 500.milliseconds

  import start.request._
  client ! CommandWrapper(SetRequestTimeout(Duration.Inf)) // cancel timeout

  val tmpFile = File.createTempFile("chunked-receiver", ".tmp", new File("/tmp"))
  tmpFile.deleteOnExit()
  val output = new FileOutputStream(tmpFile)
  val Some(HttpHeaders.`Content-Type`(ContentType(multipart: MultipartMediaType, _))) = header[HttpHeaders.`Content-Type`]
  val boundary = multipart.parameters("boundary")

  logger.info(s"Starting upload $method $uri with multipart boundary '$boundary' writing to $tmpFile")
  var bytesWritten = 0L

  def receive = {
    case c: MessageChunk =>
      logger.debug(s"Got ${c.data.length} bytes of chunked request $method $uri")

      output.write(c.data.toByteArray)
      bytesWritten += c.data.length

    case e: ChunkedMessageEnd =>
      logger.info(s"Uploaded chunked request $method $uri")
      output.close()

      try {
        uploadToStorage(uri, tmpFile)
      }
      catch {
        case NonFatal(exception) ⇒
          val error = ErrorBody(ErrorCode.UPLOAD_FAILED, Some(exception.toString))
          logger.error(s"Upload failed #${error.errorId}", exception)
          client ! HttpResponse(status = StatusCodes.InternalServerError,
            HttpEntity(`application/json`, error.serializeToString)
          )
          client ! CommandWrapper(SetRequestTimeout(2.seconds)) // reset timeout
          context.stop(self)
      }

    case w: WatchMedia ⇒
      import com.hypertino.hyperbus.model.MessagingContext.Implicits.emptyContext
      import monix.execution.Scheduler.Implicits.global // todo: inject/config
      Task.gather {
        w.mediaIdMap.map { case (name, mediaId) ⇒
          hyperbus
            .ask(MediaFileGet(mediaId))
            .timeout(processingTimeout)
            .materialize.map(r ⇒ mediaId → r)
        }
      }.map{ results ⇒
        val processed =
          try {
            results.forall {
              case (_, Success(Ok(m: Media, _))) if m.status == MediaStatus.NORMAL ⇒ true
              case (mediaId, other) ⇒ {
                logger.debug(s"Still waiting for media $mediaId, status: $other")
                false
              }
            }
          }
          catch {
            case e: Throwable ⇒
              logger.error("Exception while waiting for processing", e)
              false
          }

        if (processed) {
          import com.hypertino.binders.json.JsonBinders._
          // todo: + Location header!
          client ! HttpResponse(status = StatusCodes.Created,
            HttpEntity(`application/json`, "{\"media_ids\":" + w.mediaIdMap.toJson + "}")
          )
          client ! CommandWrapper(SetRequestTimeout(2.seconds)) // reset timeout
          context.stop(self)
        }
        else {
          if (w.ttl > System.currentTimeMillis()) {
            context.system.scheduler.scheduleOnce(checkProcessingEach, self, w)(context.dispatcher)
          }
          else {
            import com.hypertino.binders.value._
            val error = ErrorBody(ErrorCode.PROCESSING_TIMEOUT, Some("Timed-out while waiting for the processing"), extra = w.mediaIdMap.toValue)
            logger.error(s"Didn't get processing result for ${w.mediaIdMap} #${error.errorId}")
            client ! HttpResponse(status = StatusCodes.GatewayTimeout,
              HttpEntity(`application/json`, error.serializeToString)
            )
            client ! CommandWrapper(SetRequestTimeout(2.seconds)) // reset timeout
            context.stop(self)
          }
        }
      }.runAsync
  }

  import collection.JavaConverters._

  def fileNameForPart(part: MIMEPart): Option[String] = paramForPart(part, "filename")
  def nameForPart(part: MIMEPart): Option[String] = paramForPart(part, "name")

  def paramForPart(part: MIMEPart, param: String): Option[String] =
    for {
      dispHeader <- part.getHeader("Content-Disposition").asScala.lift(0)
      Right(disp: `Content-Disposition`) = HttpParser.parseHeader(RawHeader("Content-Disposition", dispHeader))
      name <- disp.parameters.get(param)
    } yield name

  def uploadToStorage(uri: Uri, file: File): Unit = {
    try {
      val path = uri.path.toString().split("/").filterNot(_.isEmpty)
      val (bucketName, folder) = if (path.isEmpty) {
        (defaultBucketName, "")
      } else {
        (path.head, path.tail.mkString("/"))
      }

      val message = new MIMEMessage(new FileInputStream(tmpFile), boundary)
      // caution: the next line will read the complete file regardless of its size
      val parts = message.getAttachments.asScala
      val mediaIdMap = parts.zipWithIndex.map { case (part, index) =>
        val contentType = part.getContentType
        val id = IdGenerator.create()
        val subFolders = id.substring(id.length-4, id.length-2) + "/" + id.substring(id.length-2) + "/"
        val fileName = subFolders + id + fileNameForPart(part).map(extension).getOrElse("")
        val name = nameForPart(part).getOrElse(index.toString)
        val fullFileName = if (folder.isEmpty) fileName else folder + "/" + fileName
        val url = storageClient.upload(bucketName, fullFileName, part.read(), Option(contentType))
        val mediaId = MediaIdUtil.mediaId(url)

        if (directTransform) {
          import com.hypertino.hyperbus.model.MessagingContext.Implicits.emptyContext
          import monix.execution.Scheduler.Implicits.global // todo: inject/config
          hyperbus
            .ask(MediaFilesPost(CreateMediaVersions(url)))
            .onErrorRecover {
              case NonFatal(e) ⇒
                logger.error(s"Can't create versions for $url", e)
                Unit
            }
            .runAsync
        }

        name → mediaId
      } toMap

      val w = WatchMedia(mediaIdMap, System.currentTimeMillis() + processingTimeout.toMillis)
      import context.dispatcher
      context.system.scheduler.scheduleOnce(checkProcessingEach, self, w)
    }
    finally {
      tmpFile.delete()
    }
  }

  protected def extension(fileName: String): String = {
    val li = fileName.lastIndexOf(".")
    if (li > 0) {
      fileName.substring(li)
    }
    else {
      ""
    }
  }
}