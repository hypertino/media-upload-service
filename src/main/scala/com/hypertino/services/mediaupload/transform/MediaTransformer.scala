package com.hypertino.services.mediaupload.transform

import java.nio.file.Path

import com.hypertino.binders.value.Value
import com.hypertino.mediaupload.api.Media

trait MediaTransformer {
  def transform(media: Media, originalFileName: String, originalPath: Path): Value
}
