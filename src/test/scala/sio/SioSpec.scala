package sio

import org.scalatest._
import flatspec.AnyFlatSpec
import matchers.should._

class SioSpec extends AnyFlatSpec with Matchers:

  it should "run and return an value" in {
    val sio = Sio.succeedNow(42)

    sio.runUnsafeSync shouldBe 42
  }

  it should "run side effects only if run" in {
    val stream = new java.io.ByteArrayOutputStream()

    scala.Console.withOut(stream) {
      val sio = Sio.succeed(println("Hello, world!"))

      sio.runUnsafeSync shouldBe ()
      sio.runUnsafeSync shouldBe ()
    }

    val prints = stream.toString().split('\n')

    prints.length shouldBe 2
    prints(0) shouldBe "Hello, world!"
    prints(1) shouldBe "Hello, world!"
  }

  it should "defere computation" in {
    val stream = new java.io.ByteArrayOutputStream()

    scala.Console.withOut(stream) {

      val sio = Sio.async { complete =>
        println("Hello, world!")
        Thread.sleep(1000)
        complete(Sio.succeedNow(42))
      }

      sio.runUnsafeSync shouldBe 42
      sio.runUnsafeSync shouldBe 42
    }

    val prints = stream.toString().split('\n')

    prints.length shouldBe 2
    prints(0) shouldBe "Hello, world!"
    prints(1) shouldBe "Hello, world!"
  }

  it should "flatMap" in {
    val sio = Sio.succeed(7)

    val mapped = sio.flatMap(a => Sio.succeedNow(a * 7)).flatMap(a => Sio.succeedNow(a - 7))

    mapped.runUnsafeSync shouldBe 42
  }
