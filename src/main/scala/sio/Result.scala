package sio

enum Result[+E, +A]:
  case Success(value: A) extends Result[Nothing, A]
  case Error(error: E) extends Result[E, Nothing]
  case Exception(throwable: Throwable) extends Result[Nothing, Nothing]
  case Killed() extends Result[Nothing, Nothing]
  case Interrupted() extends Result[Nothing, Nothing]

  def isSuccess: Boolean = this match
    case Success(_) => true
    case _          => false
