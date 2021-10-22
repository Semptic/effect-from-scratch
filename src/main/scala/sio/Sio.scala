package sio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

trait Fiber[+A]:
  def join: Sio[A]

class FiberImpl[A](sio: Sio[A]) extends Fiber[A]:
  val result = AtomicReference[Option[A]](None)
  ExecutionContext.global.execute(() => result.lazySet(Some(sio.runUnsafeSync)))

  override def join: Sio[A] =
    while (result.get().isEmpty) do Thread.sleep(100)

    Sio.succeedNow(result.get().get)

sealed trait Sio[+A]:
  final def flatMap[B](cont: A => Sio[B]): Sio[B] = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[B]          = this.flatMap(a => Sio.succeed(cont(a)))
  final def zip[B](that: Sio[B]): Sio[(A, B)]     = this.flatMap(a => that.flatMap(b => Sio.succeed((a, b))))

  final def fork: Sio[Fiber[A]] = Sio.Fork(this)

  final def runUnsafeSync: A =
    type Erased = Sio[Any]
    type Cont   = Any => Erased

    def erase[A](sio: Sio[A]): Erased = sio

    var result: Any = null
    val stack       = Stack.empty[Cont]
    var currentSio  = erase(this)
    var loop        = true

    def complete[A](value: A) =
      if (stack.isEmpty)
        result = value
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

    result.asInstanceOf[A]

object Sio:
  def succeedNow[A](value: A): Sio[A]             = Succeed(value)
  def succeed[A](thunk: => A): Sio[A]             = Effect(() => thunk)
  def async[A](f: (Sio[A] => Any) => Any): Sio[A] = Async(f)

  private case class Succeed[A](value: A)                extends Sio[A]
  private case class Effect[A](thunk: () => A)           extends Sio[A]
  private case class Async[A](f: (Sio[A] => Any) => Any) extends Sio[A]

  private case class FlatMap[A, B](sio: Sio[A], cont: A => Sio[B]) extends Sio[B]

  private case class Fork[A](sio: Sio[A]) extends Sio[Fiber[A]]
