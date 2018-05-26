package com.hypertino.services.mediaupload.transform

import java.io.FileInputStream
import java.nio.file.{Files, Path, Paths}

import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.hyperbus.util.SeqGenerator
import com.hypertino.services.mediaupload.{Dimensions, Transformation, Watermark}
import com.hypertino.services.mediaupload.impl.{DimensionsUtil, MimeTypeUtils}
import com.hypertino.services.mediaupload.storage.StorageClient
import com.sksamuel.scrimage.Image
import com.typesafe.scalalogging.StrictLogging
import net.bramp.ffmpeg.{FFmpegExecutor, FFprobe}
import net.bramp.ffmpeg.builder.FFmpegBuilder
import net.bramp.ffmpeg.probe.FFmpegStream.CodecType

class VideoMediaTransformerException(code: Int, message: String) extends Exception(message)

class VideoMediaTransformer(transformation: Transformation,
                            storageClient: StorageClient,
                            bucketName: String,
                            defaultVideoCodec: String = "libx264",
                            defaultAudioCodec: String = "aac"
                           ) extends MediaTransformer with StrictLogging {

  def transform(originalFileName: String, originalPath: Path): (Value, Value, Option[String]) = {
    import scala.collection.JavaConverters._
    val ffprobe = new FFprobe()
    val probe = ffprobe.probe(originalPath.toString)
    if (probe.error != null) {
      throw new VideoMediaTransformerException(probe.error.code, probe.error.string)
    }
    val videoStream = probe.streams.asScala.find(_.codec_type == CodecType.VIDEO).getOrElse {
      throw new VideoMediaTransformerException(-1, s"File $originalFileName doesn't contains video")
    }

    var builder = new FFmpegBuilder()
    builder.setInput(probe)
    val videoDimensions = videoStream.tags.asScala.get("rotate") match {
      case Some("90") | Some("180") => (videoStream.height, videoStream.width)
      case _ => (videoStream.width, videoStream.height)
    }
    val dimCoeff = 100
    val originalAspectRatio = videoDimensions._1 * dimCoeff / videoDimensions._2
    val complexSb = new StringBuilder

    transformation.dimensions.zipWithIndex.map { case (dimensions, i) =>
      val dimSize = DimensionsUtil.getNewDimensions(videoDimensions._1, videoDimensions._2, dimensions.width, dimensions.height)
      val dimAspectRatio = dimSize._1 * dimCoeff / dimSize._2;
      val cropS = if (dimAspectRatio > originalAspectRatio) {
        s"crop=${videoDimensions._1}:${(videoDimensions._1*dimCoeff)/dimAspectRatio},"
      } else if (dimAspectRatio < originalAspectRatio) {
        s"crop=${(videoDimensions._2*dimAspectRatio)/dimCoeff}:${videoDimensions._2},"
      } else {
        ""
      }

      complexSb.append(s"[0]${cropS}scale=${dimSize._1}:${dimSize._2}")
      complexSb.append(transformation.watermark match {
        case Some(w) =>
          val watermark = Image.fromPath(Paths.get(w.fileName))
          val coords = w.placement(watermark.width, watermark.height, dimSize._1, dimSize._2)
          s"[v$i];[1]scale=${coords._3}:${coords._4}[w$i];[v$i][w$i]overlay=${coords._1}:${coords._2}[o$i]"
        case None =>
          s"[o$i]"
      })
    }

    transformation.watermark.foreach { w =>
      builder.addInput(w.fileName)
    }
    builder.setComplexFilter(complexSb.toString)

    val tempDir = Paths.get(System.getProperty("java.io.tmpdir"), SeqGenerator.create()).toString
    val files = transformation.dimensions.zipWithIndex.map { case (tr, index) ⇒
      val (newWidth, newHeight) = DimensionsUtil.getNewDimensions(videoStream.width, videoStream.height,
        tr.width, tr.height)
      val dimensions = s"${newWidth}x$newHeight"
      val newFileName = addSuffix(originalFileName, "-" + dimensions)

      val tempFilePath: Path = Paths.get(tempDir, newFileName)
      Files.createDirectories(tempFilePath.getParent)

      var output = builder
        .addOutput(tempFilePath.toString)

      transformation.watermark.foreach {_ =>
        output
          .addExtraArgs("-map", s"[o$index]")
          .addExtraArgs("-map", "0:a")
      }

      output
        .setVideoCodec(defaultVideoCodec)
        .setAudioCodec(defaultAudioCodec)

      tr.compression.foreach {c =>
        val q = 1 + Math.min(Math.max((100 - c) * 30 / 100, 0), 30)
        output = output
          .setVideoQuality(q)
          .setAudioQuality(q)
          .setVideoMovFlags("+faststart")
      }

      builder = output.done()
      dimensions → tempFilePath
    }
    val frame1path = Paths.get(tempDir, Paths.get(originalFileName) + ".jpeg")
    builder
      .addOutput(frame1path.toString)
      .setVideoQuality(1)
      .setFrames(1)
      .done()

    val executor = new FFmpegExecutor()
    executor.createJob(builder).run()
    val versions = files.map { v =>
      val newFileName = v._2.getFileName.toString
      val stream = new FileInputStream(v._2.toString)
      val versionUri = try {
        logger.info(s"Uploading $bucketName/$newFileName")
        storageClient.upload(bucketName, newFileName, stream, MimeTypeUtils.probeContentType(originalFileName))
      } finally {
        stream.close()
        try {
          Files.delete(v._2)
        }
        catch {
          case e: Throwable =>
            logger.warn(s"Can't delete file $newFileName", e)
        }
      }
      v._1 -> Text(versionUri)
    }

    val imageMediaTransformer = new ImageMediaTransformer(
      new Transformation {
        override def watermark: Option[Watermark] = transformation.watermark
        override def dimensions: Seq[Dimensions] = transformation.thumbnails
        override def thumbnails: Seq[Dimensions] = Seq.empty
      },
      storageClient,
      bucketName
    )

    val (thumbnails,_,_) = imageMediaTransformer.transform(frame1path.getFileName.toString,frame1path)
    val frame1file = frame1path.toFile
    frame1file.deleteOnExit()
    val frame1uri = {
      val stream = new FileInputStream(frame1file)
      val newFileName = frame1path.getFileName.toString
      try {
        logger.info(s"Uploading $bucketName/$newFileName")
        storageClient.upload(bucketName, newFileName, stream, MimeTypeUtils.probeContentType(frame1file.toString))
      } finally {
        stream.close()
        try {
          frame1file.delete()
        }
        catch {
          case e: Throwable =>
            logger.warn(s"Can't delete file $frame1path", e)
        }
      }
    }

    (Obj(versions.toMap), thumbnails, Some(frame1uri))
  }
}
