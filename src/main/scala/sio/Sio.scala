package sio

import sio.Sio.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext
import scala.util.Random

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

  private final case class Done(result: Either[E, A]) extends State

  private final case class Running(waiter: List[Sio[E, A] => Any]) extends State

  private sealed abstract class Continuation:
    def handle(value: Either[Any, Any]): Erased

  private final case class ErrorContinuation(cont: Cont) extends Continuation:
    override def handle(value: Either[Any, Any]): Erased = value match
      case Right(right) => throw Exception("Illegal state: Success in error case")
      case Left(left)   => cont(left)

  private final case class SucceessContinuation(cont: Cont) extends Continuation:
    override def handle(value: Either[Any, Any]): Erased = value match
      case Right(right) => cont(right)
      case Left(left)   => throw Exception("Illegal state: Error in success case")

  private final case class ErrorAndSucceessContinuation(failure: Cont, success: Cont) extends Continuation:
    override def handle(value: Either[Any, Any]): Erased = value match
      case Left(left)   => failure(left)
      case Right(right) => success(right)

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
            case Left(left)   => callback(Sio.fail(left))
            case Right(right) => callback(Sio.succeedNow(right))

  private def complete(value: Either[E, A]) = {
    var notOk = true
    while (notOk) do
      val state = currentState.get()
      state match
        case Running(callbacks) =>
          if (currentState.compareAndSet(state, Done(value)))
            notOk = false
            val result = value match
              case Left(left)   => Sio.fail(left)
              case Right(right) => Sio.succeedNow(right)

            callbacks.foreach(cb => cb(result))
        case Done(_) =>
          throw Exception("Illegal state: Fiber completed multiple times")
  }

  private def continueOrComplete(value: Either[Any, Any]) =
    if (value.isRight)
      stack.dropWhileInPlace(_.isInstanceOf[ErrorContinuation])
    else
      stack.dropWhileInPlace(_.isInstanceOf[SucceessContinuation])

    if (stack.isEmpty)
      loop = false
      value match
        case Left(left) =>
          complete(Left(left.asInstanceOf[E]))
        case Right(right) =>
          complete(Right(right.asInstanceOf[A]))
    else
      val cont = stack.pop()
      currentSio = cont.handle(value)

  private def run(): Unit =
    while (loop) do
      currentSio match
        case Sio.SucceedNow(value) =>
          continueOrComplete(Right(value))
        case Sio.Succeed(thunk) =>
          continueOrComplete(Right(thunk()))
        case Sio.Fail(error) =>
          continueOrComplete(Left(error()))
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
          continueOrComplete(Right(()))
        case Sio.Fold(sio, failure, success) =>
          currentSio = erase(sio)

          stack.push(ErrorAndSucceessContinuation(failure.asInstanceOf[Cont], success.asInstanceOf[Cont]))

sealed trait Sio[+E, +A]:
  final def catchAll[E2, B >: A](failure: E => Sio[E2, B]): Sio[E2, B] =
    this.fold(failure, a => Sio.succeedNow(a))
  final def fold[E2, B](failure: E => Sio[E2, B], success: A => Sio[E2, B]): Sio[E2, B] =
    Sio.Fold(this, failure, success)
  final def foldBoth[E1 >: E, B](f: Either[E, A] => Sio[E1, B]): Sio[E1, B] = fold(
    e => f(Left(e)),
    a => f(Right(a))
  )

  final def flatMap[E1 >: E, B](cont: A => Sio[E1, B]): Sio[E1, B] = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[E, B]                        = this.flatMap(a => Sio.succeedNow(cont(a)))

  final def zip[E1 >: E, B](that: Sio[E1, B]): Sio[E1, (A, B)] = this.zipWith(that)((a, b) => (a, b))
  final def zipWith[E1 >: E, B, C](that: Sio[E1, B])(f: (A, B) => C): Sio[E1, C] =
    this.flatMap(a => that.flatMap(b => Sio.succeedNow(f(a, b))))
  final def zipRight[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B] = this.zipWith(that)((_, b) => b)
  final def *>[E1 >: E, B](that: Sio[E1, B]): Sio[E1, B]       = zipRight(that)

  final def repeat(n: Int): Sio[E, A] =
    @tailrec
    def repeat(n: Int, sio: Sio[E, A]): Sio[E, A] =
      if (n <= 0) sio
      else repeat(n - 1, sio.zipRight(this))

    repeat(n, this)

  final def fork: Sio[Nothing, Fiber[E, A]] = Sio.Fork(this)

  final def runUnsafeSync: Either[E, A] =
    val latch  = CountDownLatch(1)
    var result = null.asInstanceOf[Either[E, A]]

    val program = this.foldBoth(a =>
      Sio.succeed {
        result = a
        latch.countDown()
      }
    )

    program.runUnsafe
    latch.await()
    result

  final def runUnsafe: Fiber[E, A] = FiberImpl(this)

object Sio:
  private[sio] def defaultExecutionContext = ExecutionContext.global

  def fail[E](error: => E): Sio[E, Nothing] = Fail(() => error)

  def succeedNow[A](value: A): Sio[Nothing, A]             = SucceedNow(value)
  def succeed[A](thunk: => A): Sio[Nothing, A]             = Succeed(() => thunk)
  def async[E, A](f: (Sio[E, A] => Any) => Any): Sio[E, A] = Async(f)

  def shift(ec: ExecutionContext): Sio[Nothing, Unit] = Sio.Shift(ec)

  private[sio] case class Fail[E](error: () => E) extends Sio[E, Nothing]

  private[sio] case class SucceedNow[A](value: A)                   extends Sio[Nothing, A]
  private[sio] case class Succeed[A](thunk: () => A)                extends Sio[Nothing, A]
  private[sio] case class Async[E, A](f: (Sio[E, A] => Any) => Any) extends Sio[E, A]

  private[sio] case class Fold[E, E2, A, B](sio: Sio[E, A], failure: E => Sio[E2, B], success: A => Sio[E2, B])
      extends Sio[E2, B]
  private[sio] case class FlatMap[E, A, B](sio: Sio[E, A], cont: A => Sio[E, B]) extends Sio[E, B]

  private[sio] case class Fork[E, A](sio: Sio[E, A])                extends Sio[Nothing, Fiber[E, A]]
  private[sio] case class Shift(executionContext: ExecutionContext) extends Sio[Nothing, Unit]
