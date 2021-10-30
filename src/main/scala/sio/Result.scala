package sio

enum Result[+E, +A]:
  case Success(value: A)
  case Error(error: E)
  case Exception(throwable: Throwable)

  def isSuccess: Boolean = this match
    case _: Success[_, _] => true
    case _                => false

  def isError: Boolean = this match
    case _: Error[_, _] => true
    case _              => false

  def isException: Boolean = this match
    case _: Exception[_, _] => true
    case _                  => false
