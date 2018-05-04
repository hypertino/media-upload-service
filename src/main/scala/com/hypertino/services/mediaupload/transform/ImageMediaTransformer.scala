package com.hypertino.services.mediaupload.transform

import java.nio.file.{Path, Paths}

import com.hypertino.binders.value.{Null, Obj, Text, Value}
import com.hypertino.mediaupload.api.Media
import com.hypertino.services.mediaupload.Transformation
import com.hypertino.services.mediaupload.impl.{DimensionsUtil, MimeTypeUtils}
import com.hypertino.services.mediaupload.storage.StorageClient
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.nio.{JpegWriter, PngWriter}
import com.typesafe.scalalogging.StrictLogging

class ImageMediaTransformer(transformation: Transformation,
                            storageClient: StorageClient,
                            bucketName: String) extends MediaTransformer with StrictLogging {

  def transform(originalFileName: String, originalPath: Path): (Value, Value) = {
    val originalImage = Image.fromPath(originalPath)

    val versions =   transformation.dimensions.map { tr ⇒
      implicit val imageWriter = if (originalFileName.endsWith(".png")) {
        PngWriter()
      }
      else {
        JpegWriter(tr.compression.getOrElse(90), progressive=true)
      }

      val (newWidth, newHeight) = DimensionsUtil.getNewDimensions(originalImage.width, originalImage.height,
        tr.width, tr.height)

      val dimensions = s"${newWidth}x$newHeight"
      val newFileName = addSuffix(originalFileName, "-" + dimensions)

      val newImage = transformImage(originalImage, newWidth, newHeight)
      //val versionUri = config.s3.endpoint + "/" + bucketName + "/" + newFileName
      val stream = newImage.stream(imageWriter)
      val versionUri = try {
        logger.info(s"Uploading $bucketName/$newFileName")
        storageClient.upload(bucketName, newFileName, stream, MimeTypeUtils.probeContentType(originalFileName))
      } finally {
        stream.close()
      }

      dimensions → Text(versionUri)
    }
    (Obj(versions.toMap), Null)
  }

  protected def applyWatermark(originalImage: Image): Image = {
    transformation.watermark.map { w =>
      val watermark = Image.fromPath(Paths.get(w.fileName))
      val scaledWatermark = if (w.height.isDefined || w.width.isDefined) {
        watermark.scaleTo(w.width.getOrElse(watermark.width), w.height.getOrElse(watermark.height))
      } else {
        watermark
      }
      val coords = w.placement(watermark.width, watermark.height, originalImage.width, originalImage.height)
      originalImage.overlay(scaledWatermark, coords._1, coords._2)
    } getOrElse {
      originalImage
    }
  }

  protected def transformImage(originalImage: Image, newWidth: Int, newHeight: Int): Image = {
    if (originalImage.width == newWidth && originalImage.height == newHeight) {
      applyWatermark(originalImage)
    }
    else {
      val originalAspectRatio = originalImage.width.toDouble / originalImage.height.toDouble
      val targetAspectRatio = newWidth.toDouble / newHeight.toDouble
      val eps = 0.00000001
      if (Math.abs(originalAspectRatio - targetAspectRatio) < eps) {
        applyWatermark(originalImage).scaleTo(newWidth, newHeight)
      }
      else {
        val resized = if (originalAspectRatio > targetAspectRatio) {
          originalImage.resizeTo((originalImage.height.toDouble * targetAspectRatio).toInt, originalImage.height)
        }
        else {
          originalImage.resizeTo(originalImage.width, (originalImage.width.toDouble / targetAspectRatio).toInt)
        }
        applyWatermark(resized).scaleTo(newWidth,newHeight)
      }
    }
  }
}
