package sio

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

final case class Has[A] private[sio] (map: Map[String, Any])

object Has:
  def apply[A](value: A)(implicit classTag: ClassTag[A]): Has[A] =
    Has[A](Map(classTag.toString -> value))

extension [Self <: Has[_]](self: Self)
  def get[A](implicit
    @implicitNotFound("Has[${A}] is not part of ${Self}")
    ev: Self <:< Has[A],
    classTag: ClassTag[A]
  ): A =
    self.map(classTag.toString).asInstanceOf[A]

  def &[That <: Has[_]](that: That): Self & That =
    Has(self.map ++ that.map).asInstanceOf[Self & That]
