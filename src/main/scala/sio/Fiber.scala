package sio

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.mutable.Stack
import scala.concurrent.ExecutionContext

trait Fiber[+E, +A]:
  def join: Sio[E, A]

  def kill(): Sio[Nothing, Unit]

  def interrupt(): Sio[Nothing, Unit]

private class FiberImpl[E, A](
  startSio: Sio[E, A],
  startExecutionContext: ExecutionContext = Sio.defaultExecutionContext
) extends Fiber[E, A]:
  type Erased = Sio[Any, Any]
  type Cont   = Any => Erased

  private def erase[E, A](sio: Sio[E, A]): Erased = sio.asInstanceOf[Erased]

  private enum State:
    case Done(result: Result[E, A])
    case Running(waiter: List[Sio[E, A] => Any])

  private enum Continuation:
    case Succeess(cont: Cont)
    case ErrorAndSucceess(fold: Sio.Fold[Any, Any, Any, Any])

    def isSuccessContinuation: Boolean = this match
      case _: Succeess => true
      case _           => false

    def handle(result: Result[Any, Any]): Erased = this match
      case Succeess(cont) =>
        result match
          case Result.Success(success) => cont(success)
          case error                   => throw Exception(s"Illegal state: $error in success case")
      case ErrorAndSucceess(fold) =>
        result match
          case Result.Success(s)    => fold.success(s)
          case Result.Error(e)      => fold.failure(Sio.ErrorCause.Error(e))
          case Result.Exception(t)  => fold.failure(Sio.ErrorCause.Exception(t))
          case Result.Killed()      => fold.failure(Sio.ErrorCause.Killed())
          case Result.Interrupted() => fold.failure(Sio.ErrorCause.Interrupted())

  private val currentState    = AtomicReference[State](State.Running(List.empty))
  private val isInterrupted   = AtomicBoolean(false)
  private var isInterrupting  = false
  private var isInterruptible = true

  private val stack                      = Stack.empty[Continuation]
  private var currentSio                 = erase(startSio)
  private var currentEc                  = startExecutionContext
  private var loop                       = true
  private var fiberThread: Thread | Null = null

  override def join: Sio[E, A] = Sio.async(await)

  override def kill(): Sio[Nothing, Unit] =
    Sio.succeed {
      loop = false
      fiberThread.nn.interrupt() // This is not thread-safe, this must be improved
      complete(Result.Killed())
    }

  override def interrupt(): Sio[Nothing, Unit] = Sio.succeed {
    isInterrupted.set(true)
  }

  startExecutionContext.execute { () =>
    fiberThread = Thread.currentThread()
    run()
  }

  private def await(callback: Sio[E, A] => Any): Unit =
    var notOk = true

    while (notOk) do
      val state = currentState.get()

      state match
        case State.Running(callbacks) =>
          val newState = State.Running(callback :: callbacks)
          notOk = !currentState.compareAndSet(state, newState)
        case State.Done(value) =>
          notOk = false
          value match
            case Result.Success(s)    => callback(Sio.succeedNow(s))
            case Result.Error(e)      => callback(Sio.fail(e))
            case Result.Exception(t)  => callback(Sio.die(t))
            case Result.Killed()      => callback(Sio.kill())
            case Result.Interrupted() => callback(Sio.interrupt())
        case _ => throw Exception("Illigal state: currentState.get returned null")

  private def complete(value: Result[E, A]) = {
    var notOk = true
    while (notOk) do
      val state = currentState.get()
      state match
        case State.Running(callbacks) =>
          if (currentState.compareAndSet(state, State.Done(value)))
            notOk = false
            val result = value match
              case Result.Success(s)    => Sio.succeedNow(s)
              case Result.Error(e)      => Sio.fail(e)
              case Result.Exception(t)  => Sio.die(t)
              case Result.Killed()      => Sio.kill()
              case Result.Interrupted() => Sio.interrupt()

            callbacks.foreach(cb => cb(result))
        case _: State.Done =>
          throw Exception("Illegal state: Fiber completed multiple times")
        case _ => throw Exception("Illigal state: currentState.get returned null")
  }

  private def continueOrComplete(value: Result[Any, Any]) =
    if (stack.isEmpty)
      loop = false
      value match
        case Result.Success(s)    => complete(Result.Success(s.asInstanceOf[A]))
        case Result.Error(e)      => complete(Result.Error(e.asInstanceOf[E]))
        case Result.Exception(t)  => complete(Result.Exception(t))
        case Result.Killed()      => complete(Result.Killed())
        case Result.Interrupted() => complete(Result.Interrupted())
    else
      val cont = stack.pop()
      currentSio = cont.handle(value)

  private def shouldInterrupt =
    isInterruptible && !isInterrupting && isInterrupted.get()

  private def run(): Unit =
    while (loop) do
      if (shouldInterrupt)
        isInterrupting = true
        currentSio = Sio.interrupt()
      else
        try
          currentSio match
            case Sio.SucceedNow(value) =>
              continueOrComplete(Result.Success(value))
            case Sio.Succeed(thunk) =>
              continueOrComplete(Result.Success(thunk()))
            case Sio.Fail(error) =>
              stack.dropWhileInPlace(_.isSuccessContinuation)

              error() match
                case Sio.ErrorCause.Error(e)      => continueOrComplete(Result.Error(e))
                case Sio.ErrorCause.Exception(t)  => continueOrComplete(Result.Exception(t))
                case Sio.ErrorCause.Killed()      => continueOrComplete(Result.Killed())
                case Sio.ErrorCause.Interrupted() => continueOrComplete(Result.Interrupted())
            case Sio.FlatMap(sio, cont) =>
              currentSio = erase(sio)
              stack.push(Continuation.Succeess(cont.asInstanceOf[Cont]))
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
                Continuation.ErrorAndSucceess(
                  fold.asInstanceOf[Sio.Fold[Any, Any, Any, Any]]
                )
              )
            case Sio.Undisturbed(sio) =>
              isInterruptible = false
              currentSio = sio *> Sio.succeed {
                isInterruptible = true
              }
        catch
          case throwable: Throwable =>
            currentSio = Sio.die(throwable)
