package com.hypertino.services.mediaupload

import com.hypertino.binders.annotations.fieldName
import com.hypertino.binders.value.Value
import com.hypertino.hyperbus.Hyperbus
import com.hypertino.mediaupload.api._
import com.hypertino.service.control.api.Service
import com.typesafe.config.Config
import monix.execution.Ack.Continue
import monix.execution.Scheduler
import monix.kafka.config.AutoOffsetReset
import monix.kafka.{KafkaConsumerConfig, KafkaConsumerObservable}
import org.slf4j.LoggerFactory
import scaldi.{Injectable, Injector}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

case class MinioKafkaToHyperbusServiceConfiguration(bootstrapServers: List[String], groupId: String, topic: String, uriTemplate: String)

case class MinioEvent(
                     EventType: String,
                     Key: String
                     )

class MinioKafkaToHyperbusService(implicit val injector: Injector) extends Service with Injectable {
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val scheduler = inject[Scheduler]
  protected val hyperbus = inject[Hyperbus]
  import com.hypertino.binders.config.ConfigBinders._
  protected val config = inject[Config].read[MinioKafkaToHyperbusServiceConfiguration]("media-upload-minio-kafka")

  protected val consumerCfg = KafkaConsumerConfig.default.copy(
    bootstrapServers = config.bootstrapServers,
    groupId = config.groupId,
    autoOffsetReset = AutoOffsetReset.Earliest
  )
  protected val observable = KafkaConsumerObservable[String,String](consumerCfg, List(config.topic))

  log.info("MinioKafkaToHyperbusService started")

  protected val subscription = observable.subscribe(record ⇒ {
    if (log.isDebugEnabled()) {
      log.debug(s"Incoming event: ${record.key()} -> ${record.value()}")
    }
    try {
      import com.hypertino.hyperbus.model.MessagingContext.Implicits.emptyContext
      import com.hypertino.binders.json.JsonBinders._
      val event = record.value().parseJson[MinioEvent]
      if (event.EventType == "s3:ObjectCreated:Put") {
        val uri = config.uriTemplate.replace("${filename}", event.Key)
        hyperbus
          .ask(MediaFilesPost(CreateMediaVersions(uri)))
          .onErrorRecover {
            case NonFatal(e) ⇒
              log.error(s"Can't create versions for: ${record.key()} -> ${record.value()} / $uri", e)
              Unit
          }
          .runAsync
          .flatMap { _ ⇒
            Continue
          }
      }
      else {
        log.debug(s"Skipped event: ${record.key()} -> ${record.value()}")
        Continue
      }
    }
    catch {
      case NonFatal(e) ⇒
        log.error(s"Can't handle event: ${record.key()} -> ${record.value()}", e)
        Continue
    }
  })

  override def stopService(controlBreak: Boolean, timeout: FiniteDuration): Future[Unit] = Future {
    subscription.cancel()
    log.info("MinioKafkaToHyperbusService stopped")
  }
}