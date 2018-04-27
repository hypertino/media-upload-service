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

  def transform(originalFileName: String, originalPath: Path): (Value, Value) = {
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

    transformation.watermark.foreach { w =>
      val watermark = Image.fromPath(Paths.get(w.fileName))
      val videoDimensions = videoStream.tags.asScala.get("rotate") match {
        case Some("90") | Some("180") => (videoStream.height, videoStream.width)
        case _ => (videoStream.width, videoStream.height)
      }

      val coords = w.placement(watermark.width, watermark.height, videoDimensions._1, videoDimensions._2)
      builder = builder
        .addInput(w.fileName)
        .setComplexFilter(s"[1:v][0:v] scale2ref=${watermark.width}:${watermark.height}*sar [wm] [base]; [base][wm] overlay=x=${coords._1}:${coords._2},split=${transformation.dimensions.size}${transformation.dimensions.zipWithIndex.map(i => "[o"+i._2+"]").mkString(" ")}")
    }

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
        .setVideoWidth(newWidth)
        .setVideoHeight(newHeight)

      tr.compression.foreach {c =>
        val q = 1 + Math.min(Math.max((100 - c) * 30 / 100, 0), 30)
        output = output
          .setVideoQuality(q)
          .setAudioQuality(q)
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
    // todo: convert thumbnail from sample to display ration
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
        override def watermark: Option[Watermark] = None
        override def dimensions: Seq[Dimensions] = transformation.thumbnails
        override def thumbnails: Seq[Dimensions] = Seq.empty
      },
      storageClient,
      bucketName
    )

    val (thumbnales,_) = imageMediaTransformer.transform(frame1path.getFileName.toString,frame1path)

    (Obj(versions.toMap), thumbnales)
  }
}
