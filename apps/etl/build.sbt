ThisBuild / organization := "com.atlas"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.20"

lazy val sparkVersion = "3.5.5"
lazy val atlasRunMemoryOptions = sys.env.get("ATLAS_RUN_MEMORY").toSeq.map(size => s"-Xmx$size")

lazy val root = (project in file("."))
  .settings(
    name := "atlas-etl",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % sparkVersion,
      "org.apache.spark" %% "spark-sql" % sparkVersion,
      "com.typesafe" % "config" % "1.4.3",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    Compile / run / fork := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Compile / run / javaOptions ++= atlasRunMemoryOptions,
    javaOptions ++= Seq(
      "-Dio.netty.tryReflectionSetAccessible=true",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-exports=java.base/sun.util.calendar=ALL-UNNAMED"
    )
  )
