package not.sio

import org.scalatest.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.*
import sio.{Result, Sio}

import java.util
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class SioSpec extends AnyFlatSpec with Matchers:

  abstract class Fixture:
    var messages = new util.concurrent.ConcurrentLinkedQueue[String]()

    def sendMessage(msg: String): Unit =
      messages.add(msg)

  it should "run side effects only if run" in new Fixture:
    val sio = Sio.succeed(sendMessage("Hello, world!"))

    sio.runUnsafeSync shouldBe Result.Success(())
    sio.runUnsafeSync shouldBe Result.Success(())

    messages.size() shouldBe 2
    messages.poll() shouldBe "Hello, world!"
    messages.poll() shouldBe "Hello, world!"

  it should "defere computation" in new Fixture:
    val sio = Sio.async { complete =>
      Future {
        sendMessage("Hello, world!")
        Thread.sleep(1000)
        complete(Sio.succeed(42))
      }
    }

    sio.runUnsafeSync shouldBe Result.Success(42)
    sio.runUnsafeSync shouldBe Result.Success(42)

    messages.size() shouldBe 2
    messages.poll() shouldBe "Hello, world!"
    messages.poll() shouldBe "Hello, world!"

  it should "flatMap" in new Fixture:
    val sio = Sio.succeed(7)

    val mapped = sio
      .flatMap(a =>
        Sio.async[Nothing, Int] { complete =>
          Future {
            val result = a * 7
            complete(Sio.succeed(result))
          }
        }
      )
      .flatMap(a => Sio.succeed(a - 7))

    mapped.runUnsafeSync shouldBe Result.Success(42)

  it should "map" in new Fixture:
    val sio = Sio.succeed(7)

    val mapped = sio.map(a => a * 7).map(a => a - 7)

    mapped.runUnsafeSync shouldBe Result.Success(42)

  it should "zip" in new Fixture:
    val sio1 = Sio.succeed(8)
    val sio2 = Sio.succeed(9)

    val zipped = sio1 zip sio2

    zipped.runUnsafeSync shouldBe Result.Success((8, 9))

  it should "zipWith" in new Fixture:
    val sio1 = Sio.succeed(7)
    val sio2 = Sio.succeed(6)

    val zipped = sio1.zipWith(sio2)((a, b) => a * b)

    zipped.runUnsafeSync shouldBe Result.Success(42)

  it should "zipLeft" in new Fixture:
    (Sio.succeed(42) <* Sio.succeed(0)).runUnsafeSync shouldBe Result.Success(42)

  it should "zipRight" in new Fixture:
    (Sio.succeed(0) *> Sio.succeed(42)).runUnsafeSync shouldBe Result.Success(42)

  it should "repeat" in new Fixture:
    val sio = Sio.succeed(sendMessage("Hello, world!"))

    val repeated = sio.repeat(10)

    repeated.runUnsafeSync shouldBe Result.Success(())

    messages.size() shouldBe 11
    (0 until 11).foreach { _ =>
      messages.poll() shouldBe "Hello, world!"
    }

  it should "repeat 0 times" in new Fixture:
    val sio = Sio.succeed(sendMessage("Hello, world!"))

    val repeated = sio.repeat(0)

    repeated.runUnsafeSync shouldBe Result.Success(())

    messages.size() shouldBe 1
    messages.poll() shouldBe "Hello, world!"

  it should "be stack-safe" in new Fixture:
    val n    = 10_000_000
    var runs = 0
    val sio  = Sio.succeed(runs += 1)

    val repeated = sio.repeat(n - 1)

    repeated.runUnsafeSync shouldBe Result.Success(())

    runs shouldBe n

  it should "for comprehension" in new Fixture:
    val sio = for {
      a      <- Sio.succeed(7)
      b      <- Sio.succeed(6)
      result <- Sio.succeed(a * b)
    } yield result

    sio.runUnsafeSync shouldBe Result.Success(42)

  it should "run on multiple threads" in new Fixture:
    val sio = for {
      fiberA <- Sio
                  .async[Nothing, Int] { complete =>
                    Future {
                      Thread.sleep(2000)
                      sendMessage("Hello, world!")
                      complete(Sio.succeed(7))
                    }
                  }
                  .fork
      fiberB <- Sio
                  .async[Nothing, Int] { complete =>
                    Future {
                      Thread.sleep(1000)
                      sendMessage("Hello, sio!")
                      complete(Sio.succeed(7))
                    }
                  }
                  .fork
      _ <- Sio.succeed(sendMessage("Hi, scala 3!"))
      a <- fiberA.join
      b <- fiberA.join
      c <- fiberB.join
    } yield a * b - c

    val result = sio.runUnsafeSync

    result shouldBe Result.Success(42)

    messages.size() shouldBe 3
    messages.poll() shouldBe "Hi, scala 3!"
    messages.poll() shouldBe "Hello, sio!"
    messages.poll() shouldBe "Hello, world!"

  it should "change the execution context" in new Fixture:
    val async = Sio.async[Nothing, Long] { complete =>
      Thread.sleep(1000)
      sendMessage("Hello, sio!")
      complete(Sio.succeed(Thread.currentThread().getId))
    }

    val ec                    = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))
    var threadId: Long | Null = null

    ec.execute(() => threadId = Thread.currentThread().getId)

    while (threadId == null) {
      Thread.sleep(100)
    }

    val program = for {
      fiberA <- async.fork
      fiberB <- async.fork.shift(ec)
      fiberC <- async.fork.shift(ec)
      a      <- fiberA.join
      b      <- fiberB.join
      c      <- fiberC.join
    } yield (a, b, c)

    val Result.Success((threadIdA, threadIdB, threadIdC)) = program.runUnsafeSync

    threadIdA should not be threadId

    threadIdB shouldBe threadId
    threadIdC shouldBe threadId

  it should "fold" in new Fixture:
    Sio.fail("Noooooooo").fold(_ => Sio.succeed(42), _ => Sio.succeed(0)).runUnsafeSync shouldBe Result.Success(42)
    Sio.succeed("Yeeeesss").fold(_ => Sio.succeed(0), _ => Sio.succeed(42)).runUnsafeSync shouldBe Result.Success(42)

    Sio.fail("Noooooooo").fold(_ => Sio.fail(42), _ => Sio.succeed(0)).runUnsafeSync shouldBe Result.Error(42)
    Sio.succeed("Yeeeesss").fold(_ => Sio.succeed(0), _ => Sio.fail(42)).runUnsafeSync shouldBe Result.Error(42)

  it should "not run flatMap in case of failure" in new Fixture:
    val program = Sio
      .fail("Noooooooo")
      .map { _ =>
        sendMessage("Map")
        42
      }
      .zip(Sio.succeed(42))

    program.runUnsafeSync shouldBe Result.Error("Noooooooo")

    messages.size() shouldBe 0

  it should "catch errors" in new Fixture:
    val program = Sio
      .fail("Noooooooo")
      .map { _ =>
        sendMessage("Map")
        0
      }
      .catchError { e =>
        sendMessage(s"$e")
        Sio.succeed(())
      }
      .zipRight(Sio.succeed(42))

    program.runUnsafeSync shouldBe Result.Success(42)

    messages.size() shouldBe 1
    messages.poll() shouldBe "Noooooooo"

  it should "handle exceptions" in new Fixture:
    val program = Sio.succeed(throw Throwable("Noooooo"))

    val result = program.runUnsafeSync
    result shouldBe a[Result.Exception]
    result.asInstanceOf[Result.Exception].throwable.getMessage shouldBe "Noooooo"

  it should "recover from exceptions" in new Fixture:
    val program = Sio
      .succeed(5 / 0)
      .catchException { t =>
        sendMessage(t.getMessage)
        Sio.succeed(0)
      }

    program.runUnsafeSync shouldBe Result.Success(0)

    messages.size() shouldBe 1
    messages.poll() shouldBe "/ by zero"
