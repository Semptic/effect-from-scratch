package sio

import scala.collection.mutable.Stack

trait Sio[+A]:
  final def flatMap[B](cont: A => Sio[B]): Sio[B] = Sio.FlatMap(this, cont)
  final def map[B](cont: A => B): Sio[B]          = this.flatMap(a => Sio.succeed(cont(a)))
  final def zip[B](that: Sio[B]): Sio[(A, B)]     = this.flatMap(a => that.flatMap(b => Sio.succeed((a, b))))

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

    result.asInstanceOf[A]

object Sio:
  def succeedNow[A](value: A): Sio[A] = Succeed(value)

  def succeed[A](thunk: => A): Sio[A] = Effect(() => thunk)

  def async[A](f: (Sio[A] => Any) => Any): Sio[A] = Async(f)

  private case class Succeed[A](value: A)                extends Sio[A]
  private case class Effect[A](thunk: () => A)           extends Sio[A]
  private case class Async[A](f: (Sio[A] => Any) => Any) extends Sio[A]

  private case class FlatMap[A, B](sio: Sio[A], cont: A => Sio[B]) extends Sio[B]
