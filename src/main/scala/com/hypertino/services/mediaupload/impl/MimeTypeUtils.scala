package com.hypertino.services.mediaupload.impl

object MimeTypeUtils {
  def extension(fileName: String): String = {
    val li = fileName.lastIndexOf(".")
    if (li > 0) {
      fileName.substring(li)
    }
    else {
      ""
    }
  }

  def probeContentType(url: String): Option[String] = {
    extension(url).toLowerCase() match {
      case ".jpg" | ".jpeg" ⇒ Some("image/jpeg")
      case ".png" ⇒ Some("image/png")
      case  ".mp4" ⇒ Some("video/mp4")
      case  _ ⇒ None
    }
  }
}
