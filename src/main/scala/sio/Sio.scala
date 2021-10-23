package sio

import sio.Sio.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext
import scala.util.Random

trait Fiber[+A]:
  def join: Sio[A]

private class FiberImpl[A](startSio: Sio[A]) extends Fiber[A]:
  type Erased = Sio[Any]
  type Cont   = Any => Erased

  private def erase[A](sio: Sio[A]): Erased = sio

  private sealed trait State
  private final case class Done(result: A)                      extends State
  private final case class Running(waiter: List[Sio[A] => Any]) extends State

  private val currentState = AtomicReference[State](Running(List.empty))

  private val stack      = Stack.empty[Cont]
  private var currentSio = erase(startSio)
  private var loop       = true

  override def join: Sio[A] = Sio.async(await)

  ExecutionContext.global.execute(run _)

  private def complete(value: A) = {
    var notOk = true
    while (notOk) do
      val state = currentState.get()
      state match
        case Running(callbacks) =>
          if (currentState.compareAndSet(state, Done(value)))
            notOk = false
            val result = Sio.succeedNow(value)
            callbacks.foreach(cb => cb(result))
        case Done(_) =>
          throw new Exception("Illegal state: Fiber completed multiple times")
  }

  private def continueOrComplete(value: Any) =
    if (stack.isEmpty)
      loop = false
      complete(value.asInstanceOf[A])
    else
      val cont = stack.pop()
      currentSio = cont(value)

  private def await(callback: Sio[A] => Any): Unit =
    var notOk = true

    while (notOk) do
      val state = currentState.get()

      state match
        case Running(callbacks) =>
          val newState = Running(callback :: callbacks)
          notOk = !currentState.compareAndSet(state, newState)
        case Done(value) =>
          notOk = false
          callback(Sio.succeedNow(value))

  private def run(): Unit =
    while (loop) do
      currentSio match
        case Sio.Succeed(value) =>
          continueOrComplete(value)
        case Sio.Effect(thunk) =>
          continueOrComplete(thunk())
        case Sio.FlatMap(sio, cont) =>
          currentSio = erase(sio)
          stack.push(cont.asInstanceOf[Cont])
        case Sio.Async(f) =>
          loop = false
          f { sio =>
            currentSio = sio
            loop = true
            run()
          }
        case Sio.Fork(sio) =>
          currentSio = erase(Sio.succeedNow(new FiberImpl(sio)))

sealed trait Sio[+A]:
  final def flatMap[B](cont: A => Sio[B]): Sio[B]       = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[B]                = this.flatMap(a => Sio.succeed(cont(a)))
  final def zip[B](that: Sio[B]): Sio[(A, B)]           = this.zipWith(that)((a, b) => (a, b))
  final def zipWith[B, C](that: Sio[B])(f: (A, B) => C) = this.flatMap(a => that.flatMap(b => Sio.succeed(f(a, b))))
  final def zipRight[B](that: Sio[B]): Sio[B]           = this.zipWith(that)((_, b) => b)
  final def repeat(n: Int): Sio[A] =
    @tailrec
    def repeat(n: Int, sio: Sio[A]): Sio[A] =
      if (n <= 0) sio
      else repeat(n - 1, sio.zipRight(this))

    repeat(n, this)

  final def fork: Sio[Fiber[A]] = Sio.Fork(this)

  final def runUnsafeSync: A =
    val latch  = new CountDownLatch(1)
    var result = null.asInstanceOf[A]

    val program = this.flatMap(a =>
      Sio.succeed {
        result = a
        latch.countDown()
      }
    )

    program.runUnsafe
    latch.await()
    result

  final def runUnsafe: Fiber[A] = new FiberImpl[A](this)

object Sio:
  def succeedNow[A](value: A): Sio[A]             = Succeed(value)
  def succeed[A](thunk: => A): Sio[A]             = Effect(() => thunk)
  def async[A](f: (Sio[A] => Any) => Any): Sio[A] = Async(f)

  case class Succeed[A](value: A)                extends Sio[A]
  case class Effect[A](thunk: () => A)           extends Sio[A]
  case class Async[A](f: (Sio[A] => Any) => Any) extends Sio[A]

  case class FlatMap[A, B](sio: Sio[A], cont: A => Sio[B]) extends Sio[B]

  case class Fork[A](sio: Sio[A]) extends Sio[Fiber[A]]
