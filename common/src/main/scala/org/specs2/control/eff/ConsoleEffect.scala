package org.specs2.control.eff

import scalaz._, Scalaz._
import eff._, writer._
import syntax.writer._

object ConsoleEffect {

  case class ConsoleMessage(value: String) extends AnyVal

  type _console[R] = Console |= R
  type Console[A] = Writer[ConsoleMessage, A]


  def log[R :_console](message: String, doIt: Boolean = true): Eff[R, Unit] =
    if (doIt) tell(ConsoleMessage(message))
    else      pure(())

  def logThrowable[R :_console](t: Throwable, doIt: Boolean = true): Eff[R, Unit] =
    if (doIt) logThrowable(t)
    else      pure(())

  def logThrowable[R :_console](t: Throwable): Eff[R, Unit] =
    log(t.getMessage, doIt = true) >>
      log(t.getStackTrace.mkString("\n"), doIt = true) >>
      (if (t.getCause != null) logThrowable(t.getCause)
       else                    pure(()))

  /**
   * This interpreter prints messages to the console
   */
  def runConsole[R, U, A](w: Eff[R, A])(implicit m : Member.Aux[Console, R, U]): Eff[U, A] =
    runConsoleToPrinter(println)(w)

  /**
   * This interpreter prints messages to a printing function
   */
  def runConsoleToPrinter[R, U, A](printer: String => Unit)(w: Eff[R, A])(implicit m : Member.Aux[Console, R, U]) =
    w.runWriterUnsafe((message: ConsoleMessage) => printer(message.value))
}
