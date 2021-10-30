package sio

import sio.Sio.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

sealed trait Sio[+E, +A]:
  final def catchError[E2, B >: A](failure: E => Sio[E2, B]): Sio[E2, B] =
    this.fold(failure, a => Sio.succeedNow(a))

  final def catchException[E2 >: E, B >: A](exception: Throwable => Sio[E2, B]): Sio[E2, B] =
    Sio.Fold(
      this,
      {
        case Sio.ErrorCause.Error(e)     => Sio.fail(e)
        case Sio.ErrorCause.Exception(t) => exception(t)
      },
      s => Sio.succeedNow(s)
    )

  final def fold[E2, B](failure: E => Sio[E2, B], success: A => Sio[E2, B]): Sio[E2, B] =
    Sio.Fold(
      this,
      {
        case Sio.ErrorCause.Error(e)     => failure(e)
        case Sio.ErrorCause.Exception(t) => Sio.die(t)
      },
      success
    )

  def shift(ec: ExecutionContext): Sio[E, A] = this <* Sio.Shift(ec)

  final def flatMap[E1 >: E, B](cont: A => Sio[E1, B]): Sio[E1, B] = Sio.FlatMap(this, cont)

  final def map[B](cont: A => B): Sio[E, B] = this.flatMap(a => Sio.succeedNow(cont(a)))

  final def zip[E1 >: E, B](that: Sio[E1, B]): Sio[E1, (A, B)] = this.zipWith(that)((a, b) => (a, b))

  final def zipWith[E1 >: E, B, C](that: Sio[E1, B])(f: (A, B) => C): Sio[E1, C] =
    this.flatMap(a => that.flatMap(b => Sio.succeedNow(f(a, b))))

  final def zipRight[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B] = this.zipWith(that)((_, b) => b)

  final def *>[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B] = this.zipRight(that)

  final def zipLeft[E1 >: E, B](that: Sio[E1, B]): Sio[E1, A] = that.zipRight(this)

  final def <*[E1 >: E, B](that: Sio[E1, B]): Sio[E1, A] = this.zipLeft(that)

  final def repeat(n: Int): Sio[E, A] =
    @tailrec
    def repeat(n: Int, sio: Sio[E, A]): Sio[E, A] =
      if (n <= 0) sio
      else repeat(n - 1, sio.zipRight(this))

    repeat(n, this)

  final def fork: Sio[Nothing, Fiber[E, A]] = Sio.Fork(this)

  private def done[E1 >: E, B](f: Result[E, A] => Sio[E1, B]): Sio[E1, B] =
    Sio.Fold(
      this,
      {
        case Sio.ErrorCause.Error(e)     => f(Result.Error(e))
        case Sio.ErrorCause.Exception(t) => f(Result.Exception(t))
      },
      s => f(Result.Success(s))
    )

  final def runUnsafeSync: Result[E, A] =
    val latch                       = CountDownLatch(1)
    var result: Result[E, A] | Null = null

    val program = this.done(r =>
      Sio.succeed {
        result = r
        latch.countDown()
      }
    )

    program.runUnsafe
    latch.await()
    result.nn

  final def runUnsafe: Fiber[E, A] = FiberImpl(this)

object Sio:
  private[sio] def defaultExecutionContext = ExecutionContext.global

  def fail[E](error: => E): Sio[E, Nothing] = Fail(() => ErrorCause.Error(error))

  def die(throwable: => Throwable): Sio[Nothing, Nothing] = Fail(() => ErrorCause.Exception(throwable))

  private[sio] def succeedNow[A](value: A): Sio[Nothing, A] = SucceedNow(value)

  def succeed[A](thunk: => A): Sio[Nothing, A] = Succeed(() => thunk)

  def async[E, A](f: (Sio[E, A] => Any) => Any): Sio[E, A] = Async(f)

  private[sio] def shift(ec: ExecutionContext): Sio[Nothing, Unit] = Sio.Shift(ec)

  private[sio] case class Fail[E](error: () => ErrorCause[E]) extends Sio[E, Nothing]

  private[sio] case class SucceedNow[A](value: A) extends Sio[Nothing, A]

  private[sio] case class Succeed[A](thunk: () => A) extends Sio[Nothing, A]

  private[sio] case class Async[E, A](f: (Sio[E, A] => Any) => Any) extends Sio[E, A]

  private[sio] case class Fold[E, E2, A, B](
    sio: Sio[E, A],
    failure: ErrorCause[E] => Sio[E2, B],
    success: A => Sio[E2, B]
  ) extends Sio[E2, B]

  private[sio] case class FlatMap[E, A, B](sio: Sio[E, A], cont: A => Sio[E, B]) extends Sio[E, B]

  private[sio] case class Fork[E, A](sio: Sio[E, A]) extends Sio[Nothing, Fiber[E, A]]

  private[sio] case class Shift(executionContext: ExecutionContext) extends Sio[Nothing, Unit]

  private[sio] sealed trait ErrorCause[+E]

  private[sio] object ErrorCause:
    final case class Error[E](error: E) extends ErrorCause[E]

    final case class Exception(throwable: Throwable) extends ErrorCause[Nothing]
