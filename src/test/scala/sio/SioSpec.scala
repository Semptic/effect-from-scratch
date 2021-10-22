package sio

import org.scalatest._
import flatspec.AnyFlatSpec
import matchers.should._

class SioSpec extends AnyFlatSpec with Matchers:

  abstract class Fixture:
    var messages = scala.collection.mutable.Buffer.empty[String]
    def println(msg: String): Unit =
      messages.append(msg)

  it should "run and return an value" in new Fixture:
    val sio = Sio.succeedNow(42)

    sio.runUnsafeSync shouldBe 42

  it should "run side effects only if run" in new Fixture:
    val sio = Sio.succeed(println("Hello, world!"))

    sio.runUnsafeSync shouldBe ()
    sio.runUnsafeSync shouldBe ()

    messages.length shouldBe 2
    messages(0) shouldBe "Hello, world!"
    messages(1) shouldBe "Hello, world!"

  it should "defere computation" in new Fixture:
    val sio = Sio.async { complete =>
      println("Hello, world!")
      Thread.sleep(1000)
      complete(Sio.succeedNow(42))
    }

    sio.runUnsafeSync shouldBe 42
    sio.runUnsafeSync shouldBe 42

    messages.length shouldBe 2
    messages(0) shouldBe "Hello, world!"
    messages(1) shouldBe "Hello, world!"

  it should "flatMap" in new Fixture:
    val sio = Sio.succeed(7)

    val mapped = sio
      .flatMap(a =>
        Sio.async[Int] { complete =>
          val result = a * 7
          complete(Sio.succeedNow(result))
        }
      )
      .flatMap(a => Sio.succeedNow(a - 7))

    mapped.runUnsafeSync shouldBe 42

  it should "map" in new Fixture:
    val sio = Sio.succeed(7)

    val mapped = sio.map(a => a * 7).map(a => a - 7)

    mapped.runUnsafeSync shouldBe 42

  it should "zip" in new Fixture:
    val sio1 = Sio.succeed(8)
    val sio2 = Sio.succeed(9)

    val zipped = sio1 zip sio2

    zipped.runUnsafeSync shouldBe (8, 9)

  it should "for comprehension" in new Fixture:
    val sio = for {
      a      <- Sio.succeed(7)
      b      <- Sio.succeed(6)
      result <- Sio.succeed(a * b)
    } yield result

    sio.runUnsafeSync shouldBe 42

  it should "run on multiple threads" in new Fixture:
    val sio = for {
      fiberA <- Sio
                  .async[Int] { complete =>
                    Thread.sleep(2000)
                    println("Hello, world!")
                    complete(Sio.succeedNow(7))
                  }
                  .fork
      fiberB <- Sio
                  .async[Int] { complete =>
                    Thread.sleep(1000)
                    println("Hello, sio!")
                    complete(Sio.succeedNow(7))
                  }
                  .fork
      _ <- Sio.succeed(println("Hi, scala 3!"))
      a <- fiberA.join
      b <- fiberA.join
      c <- fiberB.join
    } yield a * b - c

    def runTest() =
      messages.clear()

      val result = sio.runUnsafeSync

      result shouldBe 42

      messages.length shouldBe 3
      messages(0) shouldBe "Hi, scala 3!"
      messages(1) shouldBe "Hello, sio!"
      messages(2) shouldBe "Hello, world!"

    withClue("First run:") {
      runTest()
    }
    withClue("Second run:") {
      runTest()
    }
