package com.hypertino.services.mediaupload.transform

import java.nio.file.Path

import com.hypertino.binders.value.Value
import com.hypertino.mediaupload.api.Media

trait MediaTransformer {
  def transform(media: Media, originalFileName: String, originalPath: Path): Value

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

  protected def probeContentType(url: String): Option[String] = {
    extension(url).toLowerCase() match {
      case ".jpg" | ".jpeg" ⇒ Some("image/jpeg")
      case ".png" ⇒ Some("image/png")
      case  ".mp4" ⇒ Some("video/mp4")
      case  _ ⇒ None
    }
  }
}
