package sio

sealed trait Result[+E, +A]:
  def isSuccess: Boolean

  def isError: Boolean

  def isException: Boolean

object Result:
  final case class Success[A](value: A) extends Result[Nothing, A]:
    def isSuccess: Boolean = true

    def isError: Boolean = false

    def isException: Boolean = false

  final case class Error[E](error: E) extends Result[E, Nothing]:
    def isSuccess: Boolean = false

    def isError: Boolean = true

    def isException: Boolean = false

  final case class Exception(throwable: Throwable) extends Result[Nothing, Nothing]:
    def isSuccess: Boolean = false

    def isError: Boolean = false

    def isException: Boolean = true
