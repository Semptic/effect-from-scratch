package sio

import org.scalatest.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.*

import java.util
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SioSpec extends AnyFlatSpec with Matchers:

  abstract class Fixture:
    var messages = new util.concurrent.ConcurrentLinkedQueue[String]()

    def println(msg: String): Unit =
      messages.add(msg)

  it should "run and return an value" in new Fixture:
    val sio = Sio.succeedNow(42)

    sio.runUnsafeSync shouldBe 42

  it should "run side effects only if run" in new Fixture:
    val sio = Sio.succeed(println("Hello, world!"))

    sio.runUnsafeSync shouldBe ()
    sio.runUnsafeSync shouldBe ()

    messages.size() shouldBe 2
    messages.poll() shouldBe "Hello, world!"
    messages.poll() shouldBe "Hello, world!"

  it should "defere computation" in new Fixture:
    val sio = Sio.async { complete =>
      Future {
        println("Hello, world!")
        Thread.sleep(1000)
        complete(Sio.succeedNow(42))
      }
    }

    sio.runUnsafeSync shouldBe 42
    sio.runUnsafeSync shouldBe 42

    messages.size() shouldBe 2
    messages.poll() shouldBe "Hello, world!"
    messages.poll() shouldBe "Hello, world!"

  it should "flatMap" in new Fixture:
    val sio = Sio.succeed(7)

    val mapped = sio
      .flatMap(a =>
        Sio.async[Int] { complete =>
          Future {
            val result = a * 7
            complete(Sio.succeedNow(result))
          }
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

  it should "zipWith" in new Fixture:
    val sio1 = Sio.succeed(7)
    val sio2 = Sio.succeed(6)

    val zipped = sio1.zipWith(sio2)((a, b) => a * b)

    zipped.runUnsafeSync shouldBe 42

  it should "zipRight" in new Fixture:
    val sio1 = Sio.succeed(0)
    val sio2 = Sio.succeed(42)

    val zipped = sio1.zipRight(sio2)

    zipped.runUnsafeSync shouldBe 42

  it should "repeat" in new Fixture:
    val sio = Sio.succeed(println("Hello, world!"))

    val repeated = sio.repeat(10)

    repeated.runUnsafeSync shouldBe ()

    messages.size() shouldBe 11
    (0 until 11).foreach { _ =>
      messages.poll() shouldBe "Hello, world!"
    }

  it should "repeat 0 times" in new Fixture:
    val sio = Sio.succeed(println("Hello, world!"))

    val repeated = sio.repeat(0)

    repeated.runUnsafeSync shouldBe ()

    messages.size() shouldBe 1
    messages.poll() shouldBe "Hello, world!"

  it should "be stack-safe" in new Fixture:
    val n    = 10_000_000
    var runs = 0
    val sio  = Sio.succeed(runs += 1)

    val repeated = sio.repeat(n - 1)

    repeated.runUnsafeSync shouldBe ()

    runs shouldBe n

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
                    Future {
                      Thread.sleep(2000)
                      println("Hello, world!")
                      complete(Sio.succeedNow(7))
                    }
                  }
                  .fork
      fiberB <- Sio
                  .async[Int] { complete =>
                    Future {
                      Thread.sleep(1000)
                      println("Hello, sio!")
                      complete(Sio.succeedNow(7))
                    }
                  }
                  .fork
      _ <- Sio.succeed(println("Hi, scala 3!"))
      a <- fiberA.join
      b <- fiberA.join
      c <- fiberB.join
    } yield a * b - c

    val result = sio.runUnsafeSync

    result shouldBe 42

    messages.size() shouldBe 3
    messages.poll() shouldBe "Hi, scala 3!"
    messages.poll() shouldBe "Hello, sio!"
    messages.poll() shouldBe "Hello, world!"
