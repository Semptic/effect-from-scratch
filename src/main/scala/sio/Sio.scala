package sio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext
import scala.util.Random

trait Fiber[+A]:
  def join: Sio[A]

private class FiberImpl[A](startSio: Sio[A]) extends Fiber[A]:
  sealed trait State
  case class Done(result: A)                      extends State
  case class Running(waiter: List[Sio[A] => Any]) extends State

  private val currentState = AtomicReference[State](Running(List.empty))
  ExecutionContext.global.execute(run _)

  private def run() =
    type Erased = Sio[Any]
    type Cont   = Any => Erased

    def erase[A](sio: Sio[A]): Erased = sio

    val stack      = Stack.empty[Cont]
    var currentSio = erase(startSio)
    var loop       = true

    def complete(value: Any) =
      if (stack.isEmpty)
        loop = false

        var notOk = true
        while (notOk) do
          val state = currentState.get()
          state match
            case Running(callbacks) =>
              if (currentState.compareAndSet(state, Done(value.asInstanceOf[A])))
                notOk = false
                val result = Sio.succeedNow(value.asInstanceOf[A])
                callbacks.foreach(cb => cb(result))
            case Done(_) =>
              throw new Exception("Illegal state: Fiber completed multiple times")
      else
        val cont = stack.pop()
        currentSio = cont(value)

    def runloop(): Unit =
      while (loop) do
        currentSio match
          case Sio.Succeed(value) =>
            complete(value)
          case Sio.Effect(thunk) =>
            complete(thunk())
          case Sio.FlatMap(sio, cont) =>
            currentSio = erase(sio)
            stack.push(cont.asInstanceOf[Cont])
          case Sio.Async(f) =>
            loop = false
            f { sio =>
              currentSio = sio
              loop = true
              runloop()
            }
          case Sio.Fork(sio) =>
            currentSio = erase(Sio.succeedNow(new FiberImpl(sio)))

    runloop()

  def await(callback: Sio[A] => Any): Unit =
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

  override def join: Sio[A] =
    Sio.async(await)

sealed trait Sio[+A]:
  final def flatMap[B](cont: A => Sio[B]): Sio[B] = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[B]          = this.flatMap(a => Sio.succeed(cont(a)))
  final def zip[B](that: Sio[B]): Sio[(A, B)]     = this.flatMap(a => that.flatMap(b => Sio.succeed((a, b))))

  final def fork: Sio[Fiber[A]] = Sio.Fork(this)

  final def runUnsafeSync: A =
    val latch = new CountDownLatch(1)

    var result = null.asInstanceOf[A]
    val program = for {
      a <- Sio.async[A] { complete =>
             val result = runUnsafe.join
             complete(result)
           }
      _ <- Sio.succeed { result = a }
      _ <- Sio.succeed(latch.countDown())
    } yield ()

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
