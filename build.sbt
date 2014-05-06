import scala.io.Source

androidDefaults

name := "android-akka-cluster-demo"

version := "0.1"

versionCode := 0

scalaVersion := "2.10.3"

platformName := "android-15"

libraryDependencies ++= Seq(
  "com.typesafe.akka"             %% "akka-actor"              % "2.3.1",
  "com.typesafe.akka"             %% "akka-remote"             % "2.3.1",
  "com.typesafe.akka"             %% "akka-cluster"            % "2.3.1")

proguardOptions += Source.fromFile("project/proguard.cfg").mkString

includedClasspath in Preload += new File("src/main/resources")
