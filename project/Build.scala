import sbt.Keys._
import sbt._
import java.io.{ RandomAccessFile, File }
import java.nio.channels.FileLock

object DottyBuild extends Build {

  val travisMemLimit = List("-Xmx1g", "-Xss2m")

  val TRAVIS_BUILD = "dotty.travis.build"

  val agentOptions = List(
    // "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
    // "-agentpath:/home/dark/opt/yjp-2013-build-13072/bin/linux-x86-64/libyjpagent.so"
    // "-agentpath:/Applications/YourKit_Java_Profiler_2015_build_15052.app/Contents/Resources/bin/mac/libyjpagent.jnilib",
    // "-XX:+HeapDumpOnOutOfMemoryError", "-Xmx1g", "-Xss2m"
  )

  var partestLock: FileLock = null

  val defaults = Defaults.defaultSettings ++ Seq(
    // set sources to src/, tests to test/ and resources to resources/
    scalaSource in Compile := baseDirectory.value / "src",
    javaSource in Compile := baseDirectory.value / "src",
    scalaSource in Test := baseDirectory.value / "test",
    javaSource in Test := baseDirectory.value / "test",
    resourceDirectory in Compile := baseDirectory.value / "resources",
    unmanagedSourceDirectories in Compile := Seq((scalaSource in Compile).value),
    unmanagedSourceDirectories in Test := Seq((scalaSource in Test).value),

    // include sources in eclipse (downloads source code for all dependencies)
    //http://stackoverflow.com/questions/10472840/how-to-attach-sources-to-sbt-managed-dependencies-in-scala-ide#answer-11683728
    com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseKeys.withSource := true,

    // to get Scala 2.11
    resolvers += Resolver.sonatypeRepo("releases"),

    // get reflect and xml onboard
    libraryDependencies ++= Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value,
                                "org.scala-lang.modules" %% "scala-xml" % "1.0.1",
                                "me.d-d" % "scala-compiler" % "2.11.5-20150506-175515-8fc7635b56",
                                "org.scala-lang.modules" %% "scala-partest" % "1.0.5" % "test",
                                "jline" % "jline" % "2.12"),

    // get junit onboard
    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",

    // scalac options
    scalacOptions in Global ++= Seq("-feature", "-deprecation", "-language:_"),

    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    // enable improved incremental compilation algorithm
    incOptions := incOptions.value.withNameHashing(true),

    // enable verbose exception messages for JUnit
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "--run-listener=test.ContextEscapeDetector"),
    testOptions in Test += Tests.Cleanup({ () => if (partestLock != null) partestLock.release }),
    // when this file is locked, running test generates the files for partest
    // otherwise it just executes the tests directly
    lockPartestFile := {
      val partestLockFile = "." + File.separator + "tests" + File.separator + "partest.lock"
      partestLock = new RandomAccessFile(partestLockFile, "rw").getChannel.tryLock
    },
    runPartestRunner <<= runTask(Test, "dotty.partest.DPConsoleRunner", "") dependsOn (test in Test),

    // Adjust classpath for running dotty
    mainClass in (Compile, run) := Some("dotty.tools.dotc.Main"),
    fork in run := true,
    fork in Test := true,
    parallelExecution in Test := false,

    // http://grokbase.com/t/gg/simple-build-tool/135ke5y90p/sbt-setting-jvm-boot-paramaters-for-scala
    javaOptions <++= (managedClasspath in Runtime, packageBin in Compile) map { (attList, bin) =>
       // put the Scala {library, reflect} in the classpath
       val path = for {
         file <- attList.map(_.data)
         path = file.getAbsolutePath
       } yield "-Xbootclasspath/p:" + path
       // dotty itself needs to be in the bootclasspath
       val fullpath = ("-Xbootclasspath/a:" + bin) :: path.toList
       // System.err.println("BOOTPATH: " + fullpath)

       val travis_build = // propagate if this is a travis build
         if (sys.props.isDefinedAt(TRAVIS_BUILD))
           List(s"-D$TRAVIS_BUILD=${sys.props(TRAVIS_BUILD)}") ::: travisMemLimit
         else
           List()

       val tuning =
         if (sys.props.isDefinedAt("Oshort"))
           // Optimize for short-running applications, see https://github.com/lampepfl/dotty/issues/222
           List("-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1")
        else
          List()

      tuning ::: agentOptions ::: travis_build ::: fullpath
    }
  ) ++ addCommandAlias("partest", ";lockPartestFile;runPartestRunner")

  lazy val dotty = Project(id = "dotty", base = file("."), settings = defaults)

  lazy val benchmarkSettings = Defaults.defaultSettings ++ Seq(

    // to get Scala 2.11
    resolvers += Resolver.sonatypeRepo("releases"),

    baseDirectory in (Test,run) := (baseDirectory in dotty).value,


    libraryDependencies ++= Seq("com.storm-enroute" %% "scalameter" % "0.6" % Test,
      "com.novocode" % "junit-interface" % "0.11"),
    testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),

    // scalac options
    scalacOptions in Global ++= Seq("-feature", "-deprecation", "-language:_"),

    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),

    fork in Test := true,
    parallelExecution in Test := false,

    // http://grokbase.com/t/gg/simple-build-tool/135ke5y90p/sbt-setting-jvm-boot-paramaters-for-scala
    javaOptions <++= (dependencyClasspath in Runtime, packageBin in Compile) map { (attList, bin) =>
      // put the Scala {library, reflect, compiler} in the classpath
      val path = for {
        file <- attList.map(_.data)
        path = file.getAbsolutePath
        prefix = if (path.endsWith(".jar")) "p" else "a"
      } yield "-Xbootclasspath/" + prefix + ":" + path
      // dotty itself needs to be in the bootclasspath
      val fullpath = ("-Xbootclasspath/a:" + bin) :: path.toList
      // System.err.println("BOOTPATH: " + fullpath)

      val travis_build = // propagate if this is a travis build
        if (sys.props.isDefinedAt(TRAVIS_BUILD))
          List(s"-D$TRAVIS_BUILD=${sys.props(TRAVIS_BUILD)}")
        else
          List()
      val res = agentOptions ::: travis_build ::: fullpath
      println("Running with javaOptions: " + res)
      res
    }
  )


  lazy val benchmarks = Project(id = "dotty-bench", settings = benchmarkSettings,
    base = file("bench")) dependsOn(dotty % "compile->test")

  lazy val lockPartestFile = TaskKey[Unit]("lockPartestFile", "Creates the file lock on  ./tests/partest.lock")
  lazy val runPartestRunner = TaskKey[Unit]("runPartestRunner", "Runs partests")

}
