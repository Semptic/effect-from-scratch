package example

import sio.{Result, Sio}

import scala.util.Random

def printEffect(msg: String): Sio[Nothing, Unit] = Sio.succeed(println(msg))

def msg = "I was compiled by Scala 3. :)"

def program = Sio
  .succeed("Hello world!")
  .flatMap(printEffect)
  .zipRight(Sio.succeed(msg))
  .flatMap(printEffect)
  .zipRight(Sio.async[String, Int] { complete =>
    Random.nextInt(3) match
      case 0 => complete(Sio.succeed(12))
      case 1 => complete(Sio.fail("Bad luck"))
      case 2 => complete(Sio.die(Exception("Really bad luck")))
  })

@main def hello: Unit =
  program.runUnsafeSync match
    case Result.Success(s)   => println(s"Success: $s")
    case Result.Error(e)     => println(s"Error: $e")
    case Result.Exception(t) => println(s"Exception $t")
