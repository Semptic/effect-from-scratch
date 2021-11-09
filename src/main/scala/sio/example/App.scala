package example

import sio.{Result, Sio}

import scala.util.Random

def printEffect(msg: String): Sio[Nothing, Unit] = Sio.succeed(println(msg))

def msg = "I was compiled by Scala 3. :)"

def HelloWorldProgram = Sio
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

def PrintLine(msg: String): Sio[Nothing, Unit]      = Sio.succeed(println(msg))
def ReadLine(): Sio[Nothing, String]                = Sio.succeed(scala.io.StdIn.readLine())
def RandomNumber(maxNumber: Int): Sio[Nothing, Int] = Sio.succeed(Random.nextInt(maxNumber))

def GuessNumber(maxNumber: Int): Sio[Nothing, Int] =
  for
    _      <- PrintLine(s"Guess a number between 0 (inclusive) and $maxNumber (exclusive):")
    number <- ReadLine().flatMap(s => Sio.succeed(s.toInt))
  yield number

def CheckNumber(n: Int, maxNumber: Int): Sio[Nothing, Boolean] =
  def checkNumber(n: Int, guess: Sio[Nothing, Int]): Sio[Nothing, Boolean] =
    guess.flatMap { guess =>
      if guess == n then Sio.succeed(true)
      else
        val msg =
          if guess > n then PrintLine("Too high!")
          else PrintLine("Too low!")

        msg *> checkNumber(n, GuessNumber(maxNumber))
    }

  checkNumber(n, GuessNumber(maxNumber))

def GuessingGameProgram(maxNumber: Int) =
  for
    randomNumber <- RandomNumber(maxNumber)
    success      <- CheckNumber(randomNumber, maxNumber)
  yield success

@main def hello: Unit =
  HelloWorldProgram.runUnsafeSync match
    case Result.Success(s)   => println(s"Success: $s")
    case Result.Error(e)     => println(s"Error: $e")
    case Result.Exception(t) => println(s"Exception $t")

@main def guess: Unit =
  GuessingGameProgram(10).runUnsafeSync match
    case Result.Success(s)   => println(s"Concrats you found the number")
    case Result.Error(e)     => println(s"Error: $e")
    case Result.Exception(t) => println(s"Exception $t")
