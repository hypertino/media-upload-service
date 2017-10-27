/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.hypertino.services.mediaupload

import com.hypertino.binders.value.Value
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.hyperbus.model.{Created, DynamicBody, EmptyBody, ErrorBody, MessagingContext, NoContent, NotFound, Ok, ResponseBase}
import com.hypertino.hyperbus.subscribe.Subscribable
import com.hypertino.mediaupload.apiref.hyperstorage.{ContentDelete, ContentGet, ContentPatch, ContentPut}
import com.hypertino.service.config.ConfigLoader
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler
import scaldi.Module

import scala.collection.mutable

object MediaUploadServiceTest extends Module  with Subscribable {
  private implicit val scheduler = monix.execution.Scheduler.Implicits.global
  private implicit val mcx = MessagingContext.empty
  bind [Config] to ConfigLoader()
  bind [Scheduler] identifiedBy 'scheduler to scheduler
  bind [Hyperbus] identifiedBy 'hyperbus to injected[Hyperbus]
  val hyperStorageContent = mutable.Map[String, Value]()

  def onContentPut(implicit request: ContentPut): Task[ResponseBase] = {
    if (hyperStorageContent.put(request.path, request.body.content).isDefined) {
      Task.eval(NoContent(EmptyBody))
    }
    else {
      Task.eval(Created(EmptyBody))
    }
  }

  def onContentPatch(implicit request: ContentPatch): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒
        hyperStorageContent.put(request.path, v + request.body.content)
        Task.eval(Ok(EmptyBody))

      case None ⇒
        Task.raiseError(NotFound(ErrorBody("not-found")))
    }
  }

  def onContentDelete(implicit request: ContentDelete): Task[ResponseBase] = {
    if (hyperStorageContent.remove(request.path).isDefined) {
      Task.eval(Ok(EmptyBody))
    }
    else {
      Task.raiseError(NotFound(ErrorBody("not-found")))
    }
  }

  def onContentGet(implicit request: ContentGet): Task[ResponseBase] = {
    hyperStorageContent.get(request.path) match {
      case Some(v) ⇒ Task.eval(Ok(DynamicBody(v)))
      case None ⇒ Task.raiseError(NotFound(ErrorBody("not-found", Some(request.path))))
    }
  }

  def main(args: Array[String]): Unit = {
    val hyperbus = inject[Hyperbus]
    val handlers = hyperbus.subscribe(this)

    Thread.sleep(500)
    val mediaUploadService = new MediaUploadService()
    val minioKafkaToHyperbusService = new MinioKafkaToHyperbusService()
    val mediaUploadProxyService = new MediaUploadProxyService()
    Console.readLine()
  }
}
