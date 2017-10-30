/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload.impl

import java.net.URI

import com.roundeights.hasher.Hasher

object MediaIdUtil {
  def mediaId(url: String): String = Hasher(new URI(url).getPath).sha1.hex
}
