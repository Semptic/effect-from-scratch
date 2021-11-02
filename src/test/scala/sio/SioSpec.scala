package sio

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
    Sio
      .succeed(7)
      .flatMap(a =>
        Sio.async[Nothing, Int] { complete =>
          Future {
            val result = a * 7
            complete(Sio.succeed(result))
          }
        }
      )
      .flatMap(a => Sio.succeed(a - 7))
      .runUnsafeSync shouldBe Result.Success(42)

  it should "map" in new Fixture:
    Sio
      .succeed(7)
      .map(a => a * 7)
      .map(a => a - 7)
      .runUnsafeSync shouldBe Result.Success(42)

  it should "zip" in new Fixture:
    val zipped = Sio.succeed(8) zip Sio.succeed(9)

    zipped.runUnsafeSync shouldBe Result.Success((8, 9))

  it should "zipWith" in new Fixture:
    Sio
      .succeed(7)
      .zipWith(Sio.succeed(6))((a, b) => a * b)
      .runUnsafeSync shouldBe Result.Success(42)

  it should "zipLeft" in new Fixture:
    (Sio.succeed(42) <* Sio.succeed(0)).runUnsafeSync shouldBe Result.Success(42)

  it should "zipRight" in new Fixture:
    (Sio.succeed(0) *> Sio.succeed(42)).runUnsafeSync shouldBe Result.Success(42)

  it should "repeat" in new Fixture:
    Sio
      .succeed(sendMessage("Hello, world!"))
      .repeat(10)
      .runUnsafeSync shouldBe Result.Success(())

    messages.size() shouldBe 11
    (0 until 11).foreach { _ =>
      messages.poll() shouldBe "Hello, world!"
    }

  it should "repeat 0 times" in new Fixture:
    Sio
      .succeed(sendMessage("Hello, world!"))
      .repeat(0)
      .runUnsafeSync shouldBe Result.Success(())

    messages.size() shouldBe 1
    messages.poll() shouldBe "Hello, world!"

  it should "be stack-safe" in new Fixture:
    val n    = 10_000_000
    var runs = 0
    Sio
      .succeed(runs += 1)
      .repeat(n - 1)
      .runUnsafeSync shouldBe Result.Success(())

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
      complete(Sio.succeed(Thread.currentThread().nn.getId))
    }

    val ec                    = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1).nn)
    var threadId: Long | Null = null

    ec.execute(() => threadId = Thread.currentThread().nn.getId)

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
    Sio
      .fail("Noooooooo")
      .fold(_ => Sio.succeed(42), _ => Sio.succeed(0))
      .runUnsafeSync shouldBe Result.Success(42)
    Sio
      .succeed("Yeeeesss")
      .fold(_ => Sio.succeed(0), _ => Sio.succeed(42))
      .runUnsafeSync shouldBe Result.Success(42)

    Sio
      .fail("Noooooooo")
      .fold(_ => Sio.fail(42), _ => Sio.succeed(0))
      .runUnsafeSync shouldBe Result.Error(42)
    Sio
      .succeed("Yeeeesss")
      .fold(_ => Sio.succeed(0), _ => Sio.fail(42))
      .runUnsafeSync shouldBe Result.Error(42)

  it should "not run flatMap in case of failure" in new Fixture:
    Sio
      .fail("Noooooooo")
      .map { _ =>
        sendMessage("Map")
        42
      }
      .zip(Sio.succeed(42))
      .runUnsafeSync shouldBe Result.Error("Noooooooo")

    messages.size() shouldBe 0

  it should "catch errors" in new Fixture:
    Sio
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
      .runUnsafeSync shouldBe Result.Success(42)

    messages.size() shouldBe 1
    messages.poll() shouldBe "Noooooooo"

  it should "handle exceptions" in new Fixture:
    val result = Sio
      .succeed(throw Throwable("Noooooo"))
      .runUnsafeSync

    result shouldBe a[Result.Exception]
    result.asInstanceOf[Result.Exception].throwable.getMessage shouldBe "Noooooo"

  it should "recover from exceptions" in new Fixture:
    Sio
      .succeed(5 / 0)
      .catchException { t =>
        sendMessage(t.getMessage.nn)
        Sio.succeed(0)
      }
      .runUnsafeSync shouldBe Result.Success(0)

    messages.size() shouldBe 1
    messages.poll() shouldBe "/ by zero"

  it should "handle multiple errors in a row correctly" in new Fixture:
    Sio.succeed {
      sendMessage("success 1"); Sio.succeed("success 1")
    }.flatMap { _ =>
      sendMessage("error 1"); Sio.fail("error 1")
    }.catchError { _ =>
      sendMessage("error handler 1"); Sio.fail("error 2")
    }.catchError { _ =>
      sendMessage("error handler 2"); Sio.succeed("success 2")
    }.flatMap { _ =>
      sendMessage("exception 1"); throw Exception("exception 1")
    }.catchException { _ =>
      sendMessage("exception handler 1"); throw Exception("exception 2")
    }.catchException { _ =>
      sendMessage("exception handler 2"); Sio.succeed("success 3")
    }.runUnsafeSync shouldBe Result.Success("success 3")

    messages.size() shouldBe 7
    messages.poll() shouldBe "success 1"
    messages.poll() shouldBe "error 1"
    messages.poll() shouldBe "error handler 1"
    messages.poll() shouldBe "error handler 2"
    messages.poll() shouldBe "exception 1"
    messages.poll() shouldBe "exception handler 1"
    messages.poll() shouldBe "exception handler 2"

  it should "kill fiber" in new Fixture:
    val program = for {
      fiber1 <- Sio
                  .async[Nothing, Int] { complete =>
                    while (true) do
                      sendMessage("Running")
                      Thread.sleep(10)
                    complete(Sio.succeed(5))
                  }
                  .fork
      fiber2 <- Sio
                  .async[Nothing, Unit] { complete =>
                    Thread.sleep(500)
                    sendMessage("Killing")
                    complete(fiber1.kill())
                  }
                  .fork
      a <- fiber1.join
      b <- fiber2.join
    } yield a

    val result = program.runUnsafeSync

    result shouldBe Result.Killed()

    val reversedMessages = messages.toArray.nn.reverse

    // After killing there should no Running message
    reversedMessages(0) shouldBe "Killing"

  it should "interrupt fiber" in new Fixture:
    val program = for {
      fiber1 <- Sio.succeed {
                  sendMessage("Running")
                  Thread.sleep(100)
                  0
                }.forever.catchSome { case Sio.ErrorCause.Interrupted() =>
                  Sio.succeed(42)
                }.fork
      fiber2 <- Sio.async { complete =>
                  Thread.sleep(500)
                  sendMessage("Interrupting")
                  complete(fiber1.interrupt())
                }.fork
      a <- fiber1.join
      _ <- fiber2.join
      fiber3 <- Sio.succeed {
                  Thread.sleep(500)
                  sendMessage("Done")
                }.fork
      _ <- fiber3.join
    } yield a

    val result = program.runUnsafeSync

    result shouldBe Result.Success(42)

    val reversedMessages = messages.toArray.nn.reverse

    // After interrupting there should no Running message
    reversedMessages(0) shouldBe "Done"
    reversedMessages(1) shouldBe "Interrupting"
