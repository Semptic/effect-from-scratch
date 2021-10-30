package sio

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

trait Fiber[+E, +A]:
  def join: Sio[E, A]

  def interupt: Sio[Nothing, Unit]

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

  private final case class SucceessContinuation(cont: Cont) extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Success(success) => cont(success)
      case Result.Error(_)         => throw Exception("Illegal state: Error in success case")
      case Result.Exception(_)     => throw Exception("Illegal state: Exception in success case")

  private final case class ErrorAndSucceessContinuation(fold: Sio.Fold[Any, Any, Any, Any]) extends Continuation:
    override def handle(value: Result[Any, Any]): Erased = value match
      case Result.Success(s)   => fold.success(s)
      case Result.Error(e)     => fold.failure(Sio.ErrorCause.Error(e))
      case Result.Exception(t) => fold.failure(Sio.ErrorCause.Exception(t))

  private val currentState = AtomicReference[State](Running(List.empty))

  private val stack       = Stack.empty[Continuation]
  private var currentSio  = erase(startSio)
  private var currentEc   = startExecutionContext
  private var loop        = true
  private var fiberThread = null.asInstanceOf[Thread]

  override def join: Sio[E, A] = Sio.async(await)

  override def interupt: Sio[Nothing, Unit] =
    loop = false
    fiberThread.interrupt() // This is not thread-safe, this must be improved
    complete(Result.Exception(Exception("Interrupted")))

    Sio.succeedNow(())

  startExecutionContext.execute { () =>
    fiberThread = Thread.currentThread()
    run()
  }

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
            stack.dropWhileInPlace(_.isInstanceOf[SucceessContinuation])

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
          case fold @ Sio.Fold(sio, _, _) =>
            currentSio = erase(sio)

            stack.push(
              ErrorAndSucceessContinuation(
                fold.asInstanceOf[Sio.Fold[Any, Any, Any, Any]]
              )
            )
      catch
        case throwable: Throwable =>
          currentSio = Sio.die(throwable)
