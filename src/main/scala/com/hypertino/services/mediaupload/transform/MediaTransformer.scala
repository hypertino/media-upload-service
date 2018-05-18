package com.hypertino.services.mediaupload.transform

import java.nio.file.Path

import com.hypertino.binders.value.Value

trait MediaTransformer {
  def transform(originalFileName: String, originalPath: Path): (Value, Value, Option[String])

  protected def addSuffix(fileName: String, suffix: String): String = {
    val di = fileName.lastIndexOf('.')
    if (di >= 0) {
      fileName.substring(0,di) + suffix + fileName.substring(di)
    } else {
      fileName + suffix
    }
  }
}
