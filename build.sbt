crossScalaVersions := Seq("2.12.1", "2.11.8")

scalaVersion in Global := "2.11.8"

lazy val `media-upload-service` = project in file(".") enablePlugins Raml2Hyperbus settings (
    name := "media-upload-service",
    version := "0.1-SNAPSHOT",
    organization := "com.hypertino",
    resolvers ++= Seq(
      Resolver.sonatypeRepo("public")
    ),
    libraryDependencies ++= Seq(
      "com.hypertino" %% "hyperbus" % "0.2-SNAPSHOT",
      "com.hypertino" %% "hyperbus-t-inproc" % "0.2-SNAPSHOT",
      "com.hypertino" %% "service-control" % "0.3-SNAPSHOT",
      "com.sksamuel.scrimage" %% "scrimage-core" % "2.1.7",
      "io.minio" % "minio" % "3.0.6",
      "com.roundeights" %% "hasher" % "1.2.0",
      "com.jsuereth" %% "scala-arm" % "2.0",
      "io.monix" %% "monix-kafka-10" % "0.14",
      "com.hypertino" %% "service-config" % "0.2-SNAPSHOT" % "test",
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
