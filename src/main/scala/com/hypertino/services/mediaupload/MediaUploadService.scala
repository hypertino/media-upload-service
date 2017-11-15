/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import java.io.{File, FileOutputStream}
import java.net.{URI, URL}
import java.nio.channels.Channels
import java.nio.file.Paths
import java.util.regex.Pattern

import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Accepted, Conflict, Created, DynamicBody, ErrorBody, NotFound, Ok, Response}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.hyperbus.util.SeqGenerator
import com.hypertino.mediaupload.api.{Media, MediaFileGet, MediaFilesPost, MediaStatus}
import com.hypertino.mediaupload.apiref.hyperstorage.{ContentGet, ContentPatch, ContentPut}
import com.hypertino.service.control.api.Service
import com.hypertino.services.mediaupload.impl.MediaIdUtil
import com.hypertino.services.mediaupload.storage.StorageClient
import com.hypertino.services.mediaupload.utils.ErrorCode
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.nio.{JpegWriter, PngWriter}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import monix.execution.Scheduler
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.matching.Regex

case class Transformation(width: Option[Int], height: Option[Int], compression: Option[Int])

case class Scheme(regex: String, transformations: Seq[Transformation], bucket: Option[String]) {
  private lazy val regexPattern = new Regex(regex)
  def matches(uri: String): Boolean = regexPattern.findFirstMatchIn(uri).isDefined
}

case class Rewrite(from: String, to: String) {
  private lazy val regexPattern = Pattern.compile(from)
  def rewrite(uri: String): Option[String] = {
    val matcher = regexPattern.matcher(uri)
    if (matcher.matches()) {
      Some(matcher.replaceAll(to))
    } else {
      None
    }
  }
}

case class MediaUploadServiceConfiguration(storageUriBase: String, rewrites: Seq[Rewrite], schemes: Seq[Scheme])

class MediaUploadService(implicit val injector: Injector) extends Service with Injectable with Subscribable with StrictLogging{
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MediaUploadServiceConfiguration]("media-upload")
  protected val handlers = hyperbus.subscribe(this, logger)
  protected val storageClient = inject[StorageClient] (identified by 'mediaStorageClient)

  logger.info(s"${getClass.getName} is STARTED")

  def onMediaFilesPost(implicit post: MediaFilesPost): Task[Created[Media]] = {
    import com.hypertino.binders.value._
    val mediaId = MediaIdUtil.mediaId(post.body.originalUrl)
    val media = Media(mediaId, rewrite(post.body.originalUrl), Seq.empty, MediaStatus.PROGRESS)
    val path = hyperStorageMediaPath(mediaId)

    hyperbus
      .ask(ContentPut(path, DynamicBody(media.toValue)))
      .flatMap { _ ⇒
        transform(media)
      }
      .map { versions ⇒
        media.copy(versions = versions, status = MediaStatus.NORMAL)
      }
      .onErrorRecover {
        case NonFatal(e) ⇒
          logger.error(s"Transformation of $media is failed", e)
          media.copy(status = MediaStatus.FAILED)
      }
      .flatMap { media2: Media ⇒
        hyperbus.ask(ContentPatch(path, DynamicBody(media2.toValue))).map { _ ⇒
          Created(rewriteMedia(media2))
        }
      }
  }

  def onMediaFileGet(implicit get: MediaFileGet): Task[Response[Media]] = {
    val path = hyperStorageMediaPath(get.mediaId)
    hyperbus
      .ask(ContentGet(path))
      .flatMap {
        case Ok(mediaValueBody, _) ⇒
          import com.hypertino.binders.value._
          val media = mediaValueBody.content.to[Media]
          media.status match {
            case MediaStatus.PROGRESS | MediaStatus.SCHEDULED ⇒ Task.now(Accepted(rewriteMedia(media)))
            case MediaStatus.NORMAL ⇒ Task.now(Ok(rewriteMedia(media)))
            case MediaStatus.FAILED ⇒ Task.raiseError(Conflict(ErrorBody(ErrorCode.TRANSFORM_FAILED, Some(s"Transformation of ${media.originalUrl} failed"))))
            case MediaStatus.DELETED ⇒ Task.raiseError(NotFound(ErrorBody(ErrorCode.MEDIA_NOT_FOUND, Some(s"${get.mediaId} is not found"))))
          }
      }
  }

  protected def transform(media: Media): Task[Value] = Task.eval {
    scala.concurrent.blocking {
      config.schemes.find(_.matches(media.originalUrl)).map { scheme ⇒
        logger.info(s"Transforming ${media.originalUrl} according to $scheme")
        val tempDir = appendSeparator(System.getProperty("java.io.tmpdir"))
        val originalUrl = rewrite(media.originalUrl)
        val url = new URL(originalUrl)
        val originalTempFileName = tempDir + SeqGenerator.create() + extension(url.getFile)
        val tempFileName = tempDir + SeqGenerator.create() + extension(url.getFile)
        val (originalBucketName, originalFileName) = getOriginalBucketAndFileName(media.originalUrl)
        val bucketName = scheme.bucket.getOrElse(originalBucketName)

        import resource._

        if (originalUrl.startsWith(config.storageUriBase)) {
          storageClient.download(originalBucketName, originalFileName, originalTempFileName)
        }
        else {
          for {
            inputStream ← managed(url.openStream)
            rbc ← managed(Channels.newChannel(inputStream))
            fos ← managed(new FileOutputStream(originalTempFileName))
          } {
            fos.getChannel.transferFrom(rbc, 0, Long.MaxValue)
          }
        }

        val originalPath = Paths.get(originalTempFileName)
        val originalImage = Image.fromPath(originalPath)

        val versions = scheme.transformations.map { transformation ⇒
          implicit val imageWriter = if (media.originalUrl.endsWith(".png")) {
            PngWriter()
          }
          else {
            JpegWriter(transformation.compression.getOrElse(90), progressive=true)
          }

          val newWidth = transformation.width.getOrElse(originalImage.width)
          val newHeight = transformation.height.getOrElse(originalImage.height)
          val dimensions = s"${newWidth}x$newHeight"
          val newFileName = addSuffix(originalFileName, "-" + dimensions)

          val newImage = transformImage(originalImage, newWidth, newHeight)
          //val versionUri = config.s3.endpoint + "/" + bucketName + "/" + newFileName
          val stream = newImage.stream(imageWriter)
          val versionUri = try {
            logger.info(s"Uploading $bucketName/$newFileName")
            storageClient.upload(bucketName, newFileName, stream, probeContentType(originalUrl))
          } finally {
            stream.close()
          }

          dimensions → Text(versionUri)
        }
        Obj(versions.toMap)
      } getOrElse {
        logger.info(s"Nothing to do with ${media.originalUrl}")
        Null
      }
    }
  }

  protected def transformImage(originalImage: Image, newWidth: Int, newHeight: Int): Image = {
    if (originalImage.width == newWidth && originalImage.height == newHeight) {
      originalImage
    }
    else {
      val originalAspectRatio = originalImage.width.toDouble / originalImage.height.toDouble
      val targetAspectRatio = newWidth.toDouble / newHeight.toDouble
      val eps = 0.00000001
      if (Math.abs(originalAspectRatio - targetAspectRatio) < eps) {
        originalImage.scaleTo(newWidth, newHeight)
      }
      else {
        val resized = if (originalAspectRatio > targetAspectRatio) {
          originalImage.resizeTo((originalImage.height.toDouble * targetAspectRatio).toInt, originalImage.height)
        }
        else {
          originalImage.resizeTo(originalImage.width, (originalImage.width.toDouble / targetAspectRatio).toInt)
        }
        resized.scaleTo(newWidth,newHeight)
      }
    }
  }

//  protected def getFileName(url: String): String = {
//    val uri = new URI(url)
//    val path = uri.getPath
//    val segments = path.split('/').filter(_.nonEmpty)
//    if (url.startsWith(config.s3.endpoint) && segments.tail.nonEmpty) {
//      segments.tail.mkString("/")
//    }
//    else {
//      if (path startsWith "/") path.substring(1) else path
//    }
//  }
//
  protected def getOriginalBucketAndFileName(originalUrl: String): (String, String) = {
    val path: String = if (originalUrl.startsWith(config.storageUriBase)) {
      originalUrl.substring(config.storageUriBase.length)
    } else {
      val uri = new URI(originalUrl)
      uri.getPath
    }

    val segments = path.split('/').filter(_.nonEmpty)
    if (segments.tail.isEmpty) {
      throw new RuntimeException(s"Can't get bucket and filename from $originalUrl")
    }
    (segments.head, segments.tail.mkString("/"))
  }

  protected def addSuffix(fileName: String, suffix: String): String = {
    val di = fileName.lastIndexOf('.')
    if (di >= 0) {
      fileName.substring(0,di) + suffix + fileName.substring(di)
    } else {
      fileName + suffix
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

  protected def appendSeparator(path: String): String = {
    if (!path.endsWith(File.separator)) {
      path + File.separator
    }
    else {
      path
    }
  }

  protected def probeContentType(url: String): Option[String] = {
    extension(url).toLowerCase() match {
      case ".jpg" | ".jpeg" ⇒ Some("image/jpeg")
      case ".png" ⇒ Some("image/png")
      case  _ ⇒ None
    }
  }

  protected def rewrite(uri: String): String = config
    .rewrites
    .toIterator
    .map(_.rewrite(uri))
    .find(_.isDefined)
    .flatten
    .getOrElse(uri)

  protected  def rewriteMedia(media: Media): Media = {
    media.copy(
      originalUrl = rewrite(media.originalUrl),
      versions = media.versions match {
        case Obj(l) ⇒ Obj(l.map(kv ⇒ kv._1 → Text(rewrite(kv._2.toString))))
        case other ⇒ other
      }
    )
  }

  protected def hyperStorageMediaPath(mediaId: String): String = s"media-upload-service/files/$mediaId"

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    handlers.foreach(_.cancel())
    logger.info(s"${getClass.getName} is STOPPED")
  }
}
