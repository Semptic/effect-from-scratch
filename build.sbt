ThisBuild / organization := "sio"
ThisBuild / scalaVersion := "3.1.0"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.+" % Test,
    )
  )
