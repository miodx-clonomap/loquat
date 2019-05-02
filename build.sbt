name         := "loquat"
description  := "🍋"
organization := "com.miodx.clonomap"
version      := "2.0.0"
bucketSuffix := "era7.com"

scalaVersion  := "2.12.8"

libraryDependencies ++= Seq(
  // Internal:
  "com.miodx.common"           %% "aws-statika"     % "2.0.2",
  "com.miodx.common"           %% "datasets"        % "0.5.3",
  // Logging:
  "ch.qos.logback"              % "logback-classic" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging"   % "3.7.2",
  // Testing
  "org.scalatest"              %% "scalatest"       % "3.0.5" % Test
)

dependencyOverrides ++= Seq(
  // scala-logging 3.7.2 is bound to scala 2.12.2, check this after updating scala-logging
  "org.scala-lang" % "scala-library" % scalaVersion.value,
  "org.scala-lang" % "scala-reflect" % scalaVersion.value
)

wartremoverErrors in (Compile, compile) --= Seq(
  Wart.TryPartial
)

generateStatikaMetadataIn(Test)

// This includes tests sources in the assembled fat-jar:
fullClasspath in assembly := (fullClasspath in Test).value
