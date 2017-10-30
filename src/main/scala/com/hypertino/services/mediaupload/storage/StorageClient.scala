/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.storage

import java.io.InputStream

trait StorageClient {
  def upload(bucket: String, path: String, stream: InputStream, contentType: Option[String]): String
  def download(bucket: String, path: String, localFileName: String): Unit
}
