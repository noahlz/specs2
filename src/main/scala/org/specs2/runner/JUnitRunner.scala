package org.specs2
package runner

import _root_.org.junit.runner.notification.RunNotifier
import _root_.org.junit.runners._
import _root_.org.junit._
import _root_.org.junit.runner._
import junit.framework.AssertionFailedError
import main.Arguments
import io._
import reflect.Classes._
import execute._
import reporter._
import JUnitDescriptions._
import specification._

/**
 * The JUnitRunner class is a junit Runner class meant to be used with the RunWith annotation
 * to execute a specification as a JUnit suite.
 * 
 * The implementation is using a description Fold to fold the fragments into a tree
 * of Description objects and a Map relating each Description to a Fragment to execute. 
 *
 */
class JUnitRunner(klass: Class[_]) extends Runner with FragmentExecution {
  
  /** specification to execute */
  protected lazy val specification = tryToCreateObject[BaseSpecification](klass.getName, true, true).get
  protected lazy val content = specification.content
  /** fold object used to create descriptions */
  private val descriptions = new JUnitDescriptions(klass)
  /** extract the root Description object and the examples to execute */
  private lazy val DescriptionAndExamples(desc, executions) = descriptions.foldAll(content.fragments)
  /** @return a Description for the TestSuite */
  def getDescription = desc
  
  /** 
   * run the suite by executing each fragment related to a description:
   * * execute all fragments (including Steps which are reported as steps)
   * * for each result, report the failure/error/skipped or pending message as a
   *   junit failure or ignored event on the RunNotifier
   */
  def run(notifier: RunNotifier) {
	  executions.toStream.collect { 
	    case (desc, f @ Example(_, _)) => (desc, executeFragment(Arguments())(f)) 
      case (desc, f @ Text(_)) => (desc, executeFragment(Arguments())(f)) 
      case (desc, f @ Step(_)) => (desc, executeFragment(Arguments())(f)) 
	  }.
	    foreach {
	   	  case (desc, ExecutedResult(_, result, timer)) => {
	        notifier.fireTestStarted(desc)
	        result match {
            case f @ Failure(m, st, d) => notifier.fireTestFailure(new notification.Failure(desc, junitFailure(f)))
            case e @ Error(m, st) => notifier.fireTestFailure(new notification.Failure(desc, e.exception))
            case Pending(_) | Skipped(_)  => notifier.fireTestIgnored(desc)
            case _ => ()
          }
	        notifier.fireTestFinished(desc)
	      }
	   	  case (desc, _) => {	notifier.fireTestStarted(desc); notifier.fireTestFinished(desc) }
	    }
  }
  /** @return a Throwable expected by JUnit Failure object */
  private def junitFailure(f: Failure): Throwable = f match {
    case Failure(m, st, NoDetails()) => new SpecFailureAssertionFailedError(f.exception)
    case Failure(m, st, FailureDetails(expected, actual)) => new ComparisonFailure(m, expected, actual) {
      private val e = f.exception
      override def getStackTrace = e.getStackTrace
      override def getCause = e.getCause
      override def printStackTrace = e.printStackTrace
      override def printStackTrace(w: java.io.PrintStream) = e.printStackTrace(w)
      override def printStackTrace(w: java.io.PrintWriter) = e.printStackTrace(w)
    }
  }
}
/**
 * Factory methods to help with testing
 */
object JUnitRunner {
  def apply[T <: BaseSpecification](implicit m: ClassManifest[T]) = new JUnitRunner(m.erasure)
  def apply[T <: BaseSpecification](s: T)(implicit m: ClassManifest[T]) = new JUnitRunner(m.erasure) {
    override protected lazy val specification = s	  
  }
  def apply[T <: BaseSpecification](fragments: Fragments)(implicit m: ClassManifest[T]) = new JUnitRunner(m.erasure) {
    override protected lazy val content = fragments	  
  }
}
/**
 * This class refines the <code>AssertionFailedError</code> from junit
 * and provides the stackTrace of an exception which occurred during the specification execution
 */
class SpecFailureAssertionFailedError(e: Exception) extends AssertionFailedError(e.getMessage) {
  override def getStackTrace = e.getStackTrace
  override def getCause = e.getCause
  override def printStackTrace = e.printStackTrace
  override def printStackTrace(w: java.io.PrintStream) = e.printStackTrace(w)
  override def printStackTrace(w: java.io.PrintWriter) = e.printStackTrace(w)
}