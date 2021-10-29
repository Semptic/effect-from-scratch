package sio

import sio.Sio.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

trait Fiber[+E, +A]:
  def join: Sio[E, A]

private class FiberImpl[E, A](
  startSio: Sio[E, A],
  startExecutionContext: ExecutionContext = Sio.defaultExecutionContext
) extends Fiber[E, A]:
  type Erased = Sio[Any, Any]
  type Cont   = Any => Erased

  private def erase[E, A](sio: Sio[E, A]): Erased = sio.asInstanceOf[Erased]

  private sealed trait State

  private final case class Done(result: Result[E, A]) extends State

  private final case class Running(waiter: List[Sio[E, A] => Any]) extends State

  private sealed abstract class Continuation:
    def handle(value: Result[Any, Any]): Erased

  private final case class ErrorContinuation(cont: Cont) extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Error(error) => cont(error)
      case Result.Success(_)   => throw Exception("Illegal state: Success in error case")
      case Result.Exception(_) => throw Exception("Illegal state: Exception in error case")

  private final case class ExceptionContinuation(cont: Cont) extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Exception(error) => cont(error)
      case Result.Success(_)       => throw Exception("Illegal state: Success in exception case")
      case Result.Error(_)         => throw Exception("Illegal state: Error in exception case")

  private final case class SucceessContinuation(cont: Cont) extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Success(success) => cont(success)
      case Result.Error(_)         => throw Exception("Illegal state: Error in success case")
      case Result.Exception(_)     => throw Exception("Illegal state: Exception in success case")

  private final case class ErrorAndSucceessContinuation(failure: Cont, exception: Cont, success: Cont)
      extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Success(s)   => success(s)
      case Result.Error(e)     => failure(e)
      case Result.Exception(t) => exception(t)

  private val currentState = AtomicReference[State](Running(List.empty))

  private val stack      = Stack.empty[Continuation]
  private var currentSio = erase(startSio)
  private var currentEc  = startExecutionContext
  private var loop       = true

  override def join: Sio[E, A] = Sio.async(await)

  startExecutionContext.execute(run _)

  private def await(callback: Sio[E, A] => Any): Unit =
    var notOk = true

    while (notOk) do
      val state = currentState.get()

      state match
        case Running(callbacks) =>
          val newState = Running(callback :: callbacks)
          notOk = !currentState.compareAndSet(state, newState)
        case Done(value) =>
          notOk = false
          value match
            case Result.Success(s)   => callback(Sio.succeedNow(s))
            case Result.Error(e)     => callback(Sio.fail(e))
            case Result.Exception(t) => callback(Sio.die(t))

  private def complete(value: Result[E, A]) = {
    var notOk = true
    while (notOk) do
      val state = currentState.get()
      state match
        case Running(callbacks) =>
          if (currentState.compareAndSet(state, Done(value)))
            notOk = false
            val result = value match
              case Result.Success(s)   => Sio.succeedNow(s)
              case Result.Error(e)     => Sio.fail(e)
              case Result.Exception(t) => Sio.die(t)

            callbacks.foreach(cb => cb(result))
        case Done(_) =>
          throw Exception("Illegal state: Fiber completed multiple times")
  }

  private def continueOrComplete(value: Result[Any, Any]) =
    if (value.isException)
      stack.dropWhileInPlace(_.isInstanceOf[ErrorContinuation])
      stack.dropWhileInPlace(_.isInstanceOf[SucceessContinuation])
    else if (value.isError)
      stack.dropWhileInPlace(_.isInstanceOf[ExceptionContinuation])
      stack.dropWhileInPlace(_.isInstanceOf[SucceessContinuation])
    else
      stack.dropWhileInPlace(_.isInstanceOf[ExceptionContinuation])
      stack.dropWhileInPlace(_.isInstanceOf[ErrorContinuation])

    if (stack.isEmpty)
      loop = false
      value match
        case Result.Success(s)   => complete(Result.Success(s.asInstanceOf[A]))
        case Result.Error(e)     => complete(Result.Error(e.asInstanceOf[E]))
        case Result.Exception(t) => complete(Result.Exception(t))
    else
      val cont = stack.pop()
      currentSio = cont.handle(value)

  private def run(): Unit =
    while (loop) do
      try
        currentSio match
          case Sio.SucceedNow(value) =>
            continueOrComplete(Result.Success(value))
          case Sio.Succeed(thunk) =>
            continueOrComplete(Result.Success(thunk()))
          case Sio.Fail(error) =>
            error() match
              case Sio.ErrorCause.Error(e)     => continueOrComplete(Result.Error(e))
              case Sio.ErrorCause.Exception(t) => continueOrComplete(Result.Exception(t))
          case Sio.FlatMap(sio, cont) =>
            currentSio = erase(sio)
            stack.push(SucceessContinuation(cont.asInstanceOf[Cont]))
          case Sio.Async(f) =>
            loop = false
            f { sio =>
              currentSio = sio
              loop = true
              run()
            }
          case Sio.Fork(sio) =>
            val fiber = FiberImpl(sio, currentEc)
            currentSio = erase(Sio.succeedNow(fiber))
          case Sio.Shift(ec) =>
            currentEc = ec
            continueOrComplete(Result.Success(()))
          case Sio.Fold(sio, failure, success) =>
            currentSio = erase(sio)

            stack.push(
              ErrorAndSucceessContinuation(
                (error) => failure.asInstanceOf[Cont](Sio.ErrorCause.Error(error)),
                (exception) => failure.asInstanceOf[Cont](Sio.ErrorCause.Exception(exception.asInstanceOf[Throwable])),
                success.asInstanceOf[Cont]
              )
            )
      catch
        case throwable: Throwable =>
          currentSio = Sio.die(throwable)

sealed trait Sio[+E, +A]:
  final def catchError[E2, B >: A](failure: E => Sio[E2, B]): Sio[E2, B] =
    this.fold(failure, a => Sio.succeedNow(a))

  final def catchException[E2 >: E, B >: A](exception: Throwable => Sio[E2, B]): Sio[E2, B] =
    Sio.Fold[E, E2, A, B](
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

  final def flatMap[E1 >: E, B](cont: A => Sio[E1, B]): Sio[E1, B] = Sio.FlatMap(this, cont)

  final def map[B](cont: A => B): Sio[E, B] = this.flatMap(a => Sio.succeedNow(cont(a)))

  final def zip[E1 >: E, B](that: Sio[E1, B]): Sio[E1, (A, B)] = this.zipWith(that)((a, b) => (a, b))

  final def zipWith[E1 >: E, B, C](that: Sio[E1, B])(f: (A, B) => C): Sio[E1, C] =
    this.flatMap(a => that.flatMap(b => Sio.succeedNow(f(a, b))))

  final def zipRight[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B] = this.zipWith(that)((_, b) => b)

  final def *>[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B] = zipRight(that)

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
    val latch  = CountDownLatch(1)
    var result = null.asInstanceOf[Result[E, A]]

    val program = this.done(r =>
      Sio.succeed {
        result = r
        latch.countDown()
      }
    )

    program.runUnsafe
    latch.await()
    result

  final def runUnsafe: Fiber[E, A] = FiberImpl(this)

object Sio:
  private[sio] def defaultExecutionContext = ExecutionContext.global

  def fail[E](error: => E): Sio[E, Nothing] = Fail(() => ErrorCause.Error(error))

  def die(throwable: => Throwable): Sio[Nothing, Nothing] = Fail(() => ErrorCause.Exception(throwable))

  private[sio] def succeedNow[A](value: A): Sio[Nothing, A] = SucceedNow(value)

  def succeed[A](thunk: => A): Sio[Nothing, A] = Succeed(() => thunk)

  def async[E, A](f: (Sio[E, A] => Any) => Any): Sio[E, A] = Async(f)

  def shift(ec: ExecutionContext): Sio[Nothing, Unit] = Sio.Shift(ec)

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
