package sio

def printEffect(msg: String): Sio[Nothing, Unit] = Sio.succeed(println(msg))

def msg = "I was compiled by Scala 3. :)"

def program =
  Sio.succeed("Hello world!").flatMap(printEffect).zipRight(Sio.succeed(msg)).flatMap(printEffect)

@main def hello: Unit =
  println(program.runUnsafeSync) // Todo: Create api for Result to map/fold over it
