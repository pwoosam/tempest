name := "tempest"

version := "0.5"

scalaVersion := "2.11.5"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"

libraryDependencies += "net.openhft" % "koloboke-impl-jdk6-7" % "0.6.6"

libraryDependencies += "it.unimi.dsi" % "fastutil" % "6.6.0"

scalacOptions += "-Ywarn-unused"

test in assembly := {} // Disable tests during assembly
