crossScalaVersions := Seq(/*"2.12.3" SPRAY!,*/ "2.11.11")

scalaVersion := crossScalaVersions.value.head

lazy val `media-upload-service` = project in file(".") enablePlugins Raml2Hyperbus settings (
    name := "media-upload-service",
    version := "0.2-SNAPSHOT",
    organization := "com.hypertino",
    resolvers ++= Seq(
      Resolver.sonatypeRepo("public")
    ),
    libraryDependencies ++= Seq(
      "com.hypertino" %% "hyperbus" % "0.3-SNAPSHOT",
      "com.hypertino" %% "hyperbus-t-inproc" % "0.3-SNAPSHOT",
      "com.hypertino" %% "service-control" % "0.3.0",
      "com.sksamuel.scrimage" %% "scrimage-core" % "2.1.7",
      "io.minio" % "minio" % "3.0.6",
      "com.roundeights" %% "hasher" % "1.2.0",
      "com.jsuereth" %% "scala-arm" % "2.0",
      "io.monix" %% "monix-kafka-10" % "0.14",
      "io.spray" %% "spray-can" % "1.3.1",
      "io.spray" %% "spray-routing-shapeless2" % "1.3.3",
      "com.typesafe.akka" %% "akka-actor" % "2.4.20",
      "com.hypertino" %% "service-config" % "0.2.0" % "test",
      "ch.qos.logback" % "logback-classic" % "1.1.8" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % "test",
      compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
    ),
    ramlHyperbusSources := Seq(
      ramlSource(
        path = "api/media-upload-service-api/media-upload.raml",
        packageName = "com.hypertino.mediaupload.api",
        isResource = false
      ),
      ramlSource(
        path = "api/hyper-storage-service-api/hyperstorage.raml",
        packageName = "com.hypertino.mediaupload.apiref.hyperstorage",
        isResource = false
      )
    )
)
