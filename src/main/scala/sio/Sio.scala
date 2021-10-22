package sio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

trait Fiber[+A]:
  def join: Sio[A]

private class FiberImpl[A](startSio: Sio[A]) extends Fiber[A]:
  private val result = AtomicReference[Option[A]](None)
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
        result.lazySet(Some(value.asInstanceOf[A]))
        loop = false
      else
        val cont = stack.pop()
        currentSio = cont(value)

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
          f { sio =>
            currentSio = sio
          }
        case Sio.Fork(sio) =>
          currentSio = erase(Sio.succeedNow(new FiberImpl(sio)))

  override def join: Sio[A] =
    while (result.get().isEmpty) do Thread.sleep(100)

    Sio.succeedNow(result.get().get)

sealed trait Sio[+A]:
  final def flatMap[B](cont: A => Sio[B]): Sio[B] = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[B]          = this.flatMap(a => Sio.succeed(cont(a)))
  final def zip[B](that: Sio[B]): Sio[(A, B)]     = this.flatMap(a => that.flatMap(b => Sio.succeed((a, b))))

  final def fork: Sio[Fiber[A]] = Sio.Fork(this)

  final def runUnsafeSync: A = runUnsafe.join.asInstanceOf[Sio.Succeed[A]].value

  final def runUnsafe: Fiber[A] =
    new FiberImpl[A](this)

object Sio:
  def succeedNow[A](value: A): Sio[A]             = Succeed(value)
  def succeed[A](thunk: => A): Sio[A]             = Effect(() => thunk)
  def async[A](f: (Sio[A] => Any) => Any): Sio[A] = Async(f)

  case class Succeed[A](value: A)                extends Sio[A]
  case class Effect[A](thunk: () => A)           extends Sio[A]
  case class Async[A](f: (Sio[A] => Any) => Any) extends Sio[A]

  case class FlatMap[A, B](sio: Sio[A], cont: A => Sio[B]) extends Sio[B]

  case class Fork[A](sio: Sio[A]) extends Sio[Fiber[A]]
