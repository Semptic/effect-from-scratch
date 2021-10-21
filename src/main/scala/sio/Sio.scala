package sio

import scala.collection.mutable.Stack

trait Sio[A]:
  final def runUnsafeSync: A =
    var result     = null.asInstanceOf[A]
    var currentSio = this
    var loop       = true

    while (loop) do
      currentSio match
        case Sio.Succeed(value) => 
          result = value
          loop = false
        case Sio.Effect(thunk)  => 
          result = thunk()
          loop = false
        case Sio.Async(f) =>
          f { sio =>
            currentSio = sio
          }

    result

object Sio:
  def succeedNow[A](value: A): Sio[A] = Succeed(value)

  def succeed[A](thunk: => A): Sio[A] = Effect(() => thunk)

  def async[A](f: (Sio[A] => Any) => Any): Sio[A] = Async(f)

  case class Succeed[A](value: A)                extends Sio[A]
  case class Effect[A](thunk: () => A)           extends Sio[A]
  case class Async[A](f: (Sio[A] => Any) => Any) extends Sio[A]
