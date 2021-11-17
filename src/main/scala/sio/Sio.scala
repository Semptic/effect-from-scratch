package sio

import sio.Sio.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

type IO[E, A] = Sio[Any, E, A]

sealed trait Sio[-R, +E, +A]:
  final def provide(environment: R): Sio[Any, E, A] =
    Sio.Provide(this, environment)

  final def ensuring[R1 <: R](finalizer: Sio[R1, Nothing, Any]): Sio[R1, E, A] =
    this.foldAll(
      e => finalizer *> Sio.Fail(() => e),
      s => finalizer *> Sio.succeedNow(s)
    )

  final def catchError[R1 <: R, E1 >: E, B >: A](failure: E => Sio[R1, E1, B]): Sio[R1, E1, B] =
    this.fold(failure, a => Sio.succeedNow(a))

  final def catchSome[R1 <: R, E2 >: E, B >: A](
    failure: PartialFunction[Sio.ErrorCause[E], Sio[R1, E2, B]]
  ): Sio[R1, E2, B] =
    this.foldSome(
      failure,
      s => Sio.succeedNow(s)
    )

  final def catchException[R1 <: R, E2 >: E, B >: A](exception: Throwable => Sio[R1, E2, B]): Sio[R1, E2, B] =
    this.catchSome { case Sio.ErrorCause.Exception(t) =>
      exception(t)
    }

  final def foldAll[R1 <: R, E2, B >: A](
    failure: Sio.ErrorCause[E] => Sio[R1, E2, B],
    success: A => Sio[R1, E2, B]
  ): Sio[R1, E2, B] =
    Sio.Fold(this, failure, success)

  final def foldSome[R1 <: R, E1 >: E, B >: A](
    failure: PartialFunction[Sio.ErrorCause[E], Sio[R1, E1, B]],
    success: A => Sio[R1, E1, B]
  ): Sio[R1, E1, B] =
    this.foldAll(
      failure.orElse {
        case Sio.ErrorCause.Error(e)      => Sio.fail(e)
        case Sio.ErrorCause.Exception(t)  => Sio.die(t)
        case Sio.ErrorCause.Killed()      => Sio.kill()
        case Sio.ErrorCause.Interrupted() => Sio.interrupt()
      },
      success
    )

  final def fold[R1 <: R, E1 >: E, B >: A](failure: E => Sio[R1, E1, B], success: A => Sio[R1, E1, B]): Sio[R1, E1, B] =
    this.foldSome(
      { case Sio.ErrorCause.Error(e) => failure(e) },
      success
    )

  final def undisturbed: Sio[R, E, A] = Sio.Undisturbed(this)

  final def shift(ec: ExecutionContext): Sio[R, E, A] = this <* Sio.Shift(ec)

  final def flatMap[R1 <: R, E1 >: E, B](cont: A => Sio[R1, E1, B]): Sio[R1, E1, B] = Sio.FlatMap(this, cont)

  final def map[B](cont: A => B): Sio[R, E, B] = this.flatMap(a => Sio.succeedNow(cont(a)))

  final def zip[R1 <: R, E1 >: E, B](that: Sio[R1, E1, B]): Sio[R1, E1, (A, B)] = this.zipWith(that)((a, b) => (a, b))

  final def zipWith[R1 <: R, E1 >: E, B, C](that: => Sio[R1, E1, B])(f: (A, B) => C): Sio[R1, E1, C] =
    this.flatMap(a => that.flatMap(b => Sio.succeedNow(f(a, b))))

  final def zipRight[R1 <: R, E1 >: E, B](that: => Sio[R1, E1, B]): Sio[R1, E1, B] = this.zipWith(that)((_, b) => b)

  final def *>[R1 <: R, E1 >: E, B](that: => Sio[R1, E1, B]): Sio[R1, E1, B] = this.zipRight(that)

  final def zipLeft[R1 <: R, E1 >: E, B](that: => Sio[R1, E1, B]): Sio[R1, E1, A] = that.zipRight(this)

  final def <*[R1 <: R, E1 >: E, B](that: => Sio[R1, E1, B]): Sio[R1, E1, A] = this.zipLeft(that)

  final def repeat(n: Int): Sio[R, E, A] =
    if n <= 0 then this
    else this.zipRight(this.repeat(n - 1))

  final def repeatUntil[R1 <: R, E1 >: E, B >: A](sio: B => Sio[R1, E1, B])(predicate: B => Boolean): Sio[R1, E1, B] =
    this.flatMap(sio).flatMap { b =>
      if predicate(b) then Sio.succeedNow(b)
      else Sio.succeedNow(b).repeatUntil(sio)(predicate)
    }

  final def forever: Sio[R, E, Unit] = this *> this.forever

  final def fork: Sio[R, Nothing, Fiber[E, A]] = Sio.Fork(this)

  private def done[R1 <: R, E1 >: E, B](f: Result[E, A] => Sio[R1, E1, B]): Sio[R1, E1, B] =
    Sio.Fold(
      this,
      {
        case Sio.ErrorCause.Error(e)      => f(Result.Error(e))
        case Sio.ErrorCause.Exception(t)  => f(Result.Exception(t))
        case Sio.ErrorCause.Killed()      => f(Result.Killed())
        case Sio.ErrorCause.Interrupted() => f(Result.Interrupted())
      },
      s => f(Result.Success(s))
    )

  final def runUnsafeSync(implicit ev: Any <:< R): Result[E, A] =
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

  final def runUnsafe(implicit ev: Any <:< R): Fiber[E, A] = FiberImpl(this.asInstanceOf[IO[E, A]])

object Sio:
  private[sio] def defaultExecutionContext = ExecutionContext.global

  def environmnent[R]: Sio[R, Nothing, R] =
    access(env => Sio.succeedNow(env))

  def access[R, E, A](f: R => Sio[R, E, A]): Sio[R, E, A] =
    Access(f)

  def fail[E](error: => E): Sio[Any, E, Nothing] = Fail(() => ErrorCause.Error(error))

  def die(throwable: => Throwable): Sio[Any, Nothing, Nothing] = Fail(() => ErrorCause.Exception(throwable))

  def succeed[A](thunk: => A): Sio[Any, Nothing, A] = Succeed(() => thunk)

  def async[E, A](f: (Sio[Any, E, A] => Any) => Any): Sio[Any, E, A] = Async(f)

  private[sio] def kill(): Sio[Any, Nothing, Nothing] = Fail(() => ErrorCause.Killed())

  private[sio] def interrupt(): Sio[Any, Nothing, Nothing] = Fail(() => ErrorCause.Interrupted())

  private[sio] def succeedNow[A](value: A): Sio[Any, Nothing, A] = SucceedNow(value)

  private[sio] def shift(ec: ExecutionContext): Sio[Any, Nothing, Unit] = Sio.Shift(ec)

  private[sio] final case class Access[R, E, A](f: R => Sio[R, E, A]) extends Sio[R, E, A]

  private[sio] final case class Provide[R, E, A](sio: Sio[R, E, A], environment: R) extends Sio[Any, E, A]

  private[sio] final case class Fail[E](error: () => ErrorCause[E]) extends Sio[Any, E, Nothing]

  private[sio] final case class SucceedNow[A](value: A) extends Sio[Any, Nothing, A]

  private[sio] final case class Succeed[A](thunk: () => A) extends Sio[Any, Nothing, A]

  private[sio] final case class Async[E, A](f: (Sio[Any, E, A] => Any) => Any) extends Sio[Any, E, A]

  private[sio] final case class Fold[R, R1 <: R, E, E2, A, B](
    sio: Sio[R, E, A],
    failure: ErrorCause[E] => Sio[R1, E2, B],
    success: A => Sio[R1, E2, B]
  ) extends Sio[R1, E2, B]

  private[sio] final case class FlatMap[R, R1 <: R, E, A, B](sio: Sio[R, E, A], cont: A => Sio[R1, E, B])
      extends Sio[R1, E, B]

  private[sio] final case class Fork[R, E, A](sio: Sio[R, E, A]) extends Sio[R, Nothing, Fiber[E, A]]

  private[sio] final case class Shift(executionContext: ExecutionContext) extends Sio[Any, Nothing, Unit]

  private[sio] final case class Undisturbed[R, E, A](sio: Sio[R, E, A]) extends Sio[R, E, A]

  private[sio] enum ErrorCause[+E]:
    case Error(error: E) extends ErrorCause[E]
    case Exception(throwable: Throwable) extends ErrorCause[Nothing]
    case Killed() extends ErrorCause[Nothing]
    case Interrupted() extends ErrorCause[Nothing]
