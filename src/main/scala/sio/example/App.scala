package example

import sio.{Result, Sio}

import scala.util.Random

def printEffect(msg: String): Sio[Any, Nothing, Unit] = Sio.succeed(println(msg))

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

def PrintLine(msg: String): Sio[Any, Nothing, Unit]      = Sio.succeed(println(msg))
def ReadLine(): Sio[Any, Nothing, String]                = Sio.succeed(scala.io.StdIn.readLine())
def RandomNumber(maxNumber: Int): Sio[Any, Nothing, Int] = Sio.succeed(Random.nextInt(maxNumber))

def GuessNumber(maxNumber: Int): Sio[Any, Nothing, Int] =
  for
    _      <- PrintLine(s"Guess a number between 0 (inclusive) and $maxNumber (exclusive):")
    number <- ReadLine().flatMap(s => Sio.succeed(s.toInt))
  yield number

def CheckNumber(n: Int, guessSio: Sio[Any, Nothing, Int]): Sio[Any, Nothing, Int] =
  def printHelp(guess: Int) =
    if guess > n then PrintLine("Too high!")
    else PrintLine("Too low!")

  def newGuess(tries: Int) =
    guessSio.flatMap(guess => Sio.succeed((guess, tries + 1)))

  newGuess(0).repeatUntil { case (guess, tries) => printHelp(guess) *> newGuess(tries) }(_._1 == n)
    .map(_._2)

def GuessingGameProgram(maxNumber: Int) =
  for
    randomNumber <- RandomNumber(maxNumber)
    tries        <- CheckNumber(randomNumber, GuessNumber(maxNumber))
  yield tries

@main def hello: Unit =
  HelloWorldProgram.runUnsafeSync match
    case Result.Success(s) => println(s"Success: $s")
    case e                 => println(s"Error: $e")

@main def guess: Unit =
  GuessingGameProgram(10).runUnsafeSync match
    case Result.Success(s) => println(s"Concrats you found the number within $s tries")
    case e                 => println(s"Error: $e")
