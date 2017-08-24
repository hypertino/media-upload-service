package com.hypertino.services.mediaupload

import java.io.{File, FileInputStream, FileOutputStream}
import java.net.{URI, URL}
import java.nio.channels.Channels
import java.nio.file.{Files, Paths}
import java.util.regex.Pattern

import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Accepted, Conflict, Created, DynamicBody, ErrorBody, NotFound, Ok, Response}
import com.hypertino.hyperbus.util.SeqGenerator
import com.hypertino.mediaupload.api.{Media, MediaFileGet, MediaFilesPost, MediaStatus}
import com.hypertino.mediaupload.apiref.hyperstorage.{ContentGet, ContentPatch, ContentPut}
import com.hypertino.service.control.api.Service
import com.roundeights.hasher.Hasher
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.nio.{JpegWriter, PngWriter}
import com.typesafe.config.Config
import io.minio.MinioClient
import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.LoggerFactory
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

case class Rewrite(regex: String, target: String) {
  private lazy val regexPattern = Pattern.compile(regex)
  def rewrite(uri: String): Option[String] = {

    val matcher = regexPattern.matcher(uri)
    if (matcher.matches()) {
      Some(matcher.replaceAll(target))
    } else {
      None
    }
  }
}

case class S3Config(endpoint: String, accessKey: String, secretKey: String, bucket: Option[String])

case class MediaUploadServiceConfiguration(s3: S3Config, rewrites: Seq[Rewrite], schemes: Seq[Scheme])

class MediaUploadService(implicit val injector: Injector) extends Service with Injectable {
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MediaUploadServiceConfiguration]("media-upload")
  protected val handlers = hyperbus.subscribe(this, log)
  protected val minioClient = new MinioClient(config.s3.endpoint, config.s3.accessKey, config.s3.secretKey)

  log.info("MediaUploadService started")

  def onMediaFilesPost(implicit post: MediaFilesPost): Task[Created[Media]] = {
    import com.hypertino.binders.value._
    val mediaId = Hasher(new URI(post.body.fileUri).getPath).sha1.hex
    val media = Media(mediaId, post.body.fileUri, Seq.empty, MediaStatus.PROGRESS)
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
          log.error(s"Transformation of $media is failed", e)
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
        case Ok(mediaValueBody,_) ⇒
        import com.hypertino.binders.value._
        val media = mediaValueBody.content.to[Media]
        media.status match {
          case MediaStatus.PROGRESS | MediaStatus.SCHEDULED ⇒ Task.now(Accepted(rewriteMedia(media)))
          case MediaStatus.NORMAL ⇒ Task.now(Ok(rewriteMedia(media)))
          case MediaStatus.FAILED ⇒ Task.raiseError(Conflict(ErrorBody("transform-failed", Some(s"Transformation of ${media.fileUri} failed"))))
          case MediaStatus.DELETED ⇒ Task.raiseError(NotFound(ErrorBody("media-not-found", Some(s"${get.mediaId} is not found"))))
        }
      }
  }

  protected def transform(media: Media): Task[Value] = Task.eval {
    scala.concurrent.blocking {
      config.schemes.find(_.matches(media.fileUri)).map { scheme ⇒
        log.info(s"Transforming ${media.fileUri} according to $scheme")
        val tempDir = appendSeparator(System.getProperty("java.io.tmpdir"))
        val originalUrl = rewrite(media.fileUri)
        val url = new URL(originalUrl)
        val originalTempFileName = tempDir + SeqGenerator.create() + extension(url.getFile)
        val tempFileName = tempDir + SeqGenerator.create() + extension(url.getFile)
        val bucketName = scheme.bucket.getOrElse(config.s3.bucket.getOrElse(
          throw new IllegalArgumentException("Bucket name isn't configured")
        ))

        val fileName = getFileName(media.fileUri)
        import resource._

        if (media.fileUri.startsWith(config.s3.endpoint)) {
          val (originalBucketName,originalFileName) = getOriginalBucketAndFileName(media.fileUri)
          minioClient.getObject(originalBucketName,originalFileName, originalTempFileName)
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
          implicit val imageWriter = if (media.fileUri.endsWith(".png")) {
            PngWriter()
          }
          else {
            JpegWriter(transformation.compression.getOrElse(90), progressive=true)
          }

          val newWidth = transformation.width.getOrElse(originalImage.width)
          val newHeight = transformation.height.getOrElse(originalImage.height)
          val dimensions = s"${newWidth}x$newHeight"
          val newFileName = addSuffix(fileName, "-" + dimensions)

          val newImage = transformImage(originalImage, newWidth, newHeight)
          val versionUri = config.s3.endpoint + "/" + bucketName + "/" + newFileName

          for {stream ← managed(newImage.stream(imageWriter))
          } {
            log.info(s"Uploading $versionUri")
            minioClient.putObject(bucketName, newFileName, stream, probeContentType(originalUrl))
          }


          dimensions → Text(versionUri)
        }
        Obj(versions.toMap)
      } getOrElse {
        log.info(s"Nothing to do with ${media.fileUri}")
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

  protected def getFileName(fileUri: String): String = {
    val uri = new URI(fileUri)
    val path = uri.getPath
    val segments = path.split('/').filter(_.nonEmpty)
    if (fileUri.startsWith(config.s3.endpoint) && segments.tail.nonEmpty) {
      segments.tail.mkString("/")
    }
    else {
      if (path startsWith "/") path.substring(1) else path
    }
  }

  protected def getOriginalBucketAndFileName(fileUri: String): (String,String) = {
    val uri = new URI(fileUri)
    val path = uri.getPath
    val segments = path.split('/').filter(_.nonEmpty)
    if (segments.tail.isEmpty) {
      throw new RuntimeException(s"Can't get bucket and filename from $fileUri")
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

  protected def probeContentType(url: String): String = {
    extension(url).toLowerCase() match {
      case ".jpg" | ".jpeg" ⇒ "image/jpeg"
      case ".png" ⇒ "image/png"
      case  _ ⇒ null
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
      versions = media.versions match {
        case Obj(l) ⇒ Obj(l.map(kv ⇒ kv._1 → Text(rewrite(kv._2.toString))))
        case other ⇒ other
      }
    )
  }

  protected def hyperStorageMediaPath(mediaId: String): String = s"media-upload-service/files/$mediaId"

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    handlers.foreach(_.cancel())
    log.info("MediaUploadService stopped")
  }
}
