resolvers ++= Seq(
  Resolver.sonatypeRepo("public")
)

addSbtPlugin("com.hypertino" % "hyperbus-raml-sbt-plugin" % "0.5-SNAPSHOT")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.3")
