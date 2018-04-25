package com.hypertino.services.mediaupload.transform

import java.nio.file.{Path, Paths}

import com.hypertino.binders.value.{Obj, Text, Value}
import com.hypertino.mediaupload.api.Media
import com.hypertino.services.mediaupload.Transformation
import com.hypertino.services.mediaupload.impl.DimensionsUtil
import com.hypertino.services.mediaupload.storage.StorageClient
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.nio.{JpegWriter, PngWriter}
import com.typesafe.scalalogging.StrictLogging

class ImageMediaTransformer(transformation: Transformation, storageClient: StorageClient, bucketName: String) extends MediaTransformer with StrictLogging {

  def transform(media: Media, originalFileName: String, originalPath: Path): Value = {
    val originalImage = Image.fromPath(originalPath)
    val watermarkedImage = transformation.watermark.map { w =>
      val watermark = Image.fromPath(Paths.get(w.fileName))
      val coords = w.placement(watermark.width, watermark.height, originalImage.width, originalImage.height)
      originalImage.overlay(watermark,coords._1, coords._2)
    } getOrElse {
      originalImage
    }

    val versions =   transformation.dimensions.map { tr ⇒
      implicit val imageWriter = if (media.originalUrl.endsWith(".png")) {
        PngWriter()
      }
      else {
        JpegWriter(tr.compression.getOrElse(90), progressive=true)
      }

      val (newWidth, newHeight) = DimensionsUtil.getNewDimensions(originalImage.width, originalImage.height,
        tr.width, tr.height)

      val dimensions = s"${newWidth}x$newHeight"
      val newFileName = addSuffix(originalFileName, "-" + dimensions)

      val newImage = transformImage(watermarkedImage, newWidth, newHeight)
      //val versionUri = config.s3.endpoint + "/" + bucketName + "/" + newFileName
      val stream = newImage.stream(imageWriter)
      val versionUri = try {
        logger.info(s"Uploading $bucketName/$newFileName")
        storageClient.upload(bucketName, newFileName, stream, probeContentType(media.originalUrl))
      } finally {
        stream.close()
      }

      dimensions → Text(versionUri)
    }
    Obj(versions.toMap)
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
}
