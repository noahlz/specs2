package org.specs2.control
package producer

import eff._
import all._
import syntax.all._

import scalaz._
import Scalaz.{cata => _, _}
import Producer._

sealed trait Stream[R, A]
case class Done[R, A]() extends Stream[R, A]
case class One[R, A](a: A) extends Stream[R, A]
case class More[R, A](as: List[A], next: Producer[R, A]) extends Stream[R, A]

case class Producer[R :_safe, A](run: Eff[R, Stream[R, A]]) {

  def flatMap[B](f: A => Producer[R, B]): Producer[R, B] =
    cata[R, A, B](this)(
      done[R, B],
      (a: A) => f(a),
      (as: List[A], next: Producer[R, A]) => as.traverse(f).flatMap(emit[R, B]) append next.flatMap(f))

  def map[B](f: A => B): Producer[R, B] =
    flatMap(a => one(f(a)))

  def append(other: =>Producer[R, A]): Producer[R, A] =
    Producer(run flatMap {
      case Done() => other.run
      case One(a) => pure(More(List(a), other))
      case More(as, next) => protect(More(as, next append other))
    })

  def zip[B](other: Producer[R, B]): Producer[R, (A, B)] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => one((a, b)).run

          case More(bs, next) =>
            bs.headOption.map(b => one[R, (A, B)]((a, b))).getOrElse(done).run
        }

      case More(Nil, next) => done.run

      case More(as, nexta) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => as.headOption.map(a => one[R, (A, B)]((a, b))).getOrElse(done).run

          case More(bs, nextb) =>
            if (as.size == bs.size)
              emit(as zip bs).run
            else if (as.size < bs.size)
              (emit(as zip bs) append (nexta zip (emit(bs.drop(as.size)) append nextb))).run
            else
              (emit(as zip bs) append ((emit(as.drop(bs.size)) append nexta) zip nextb)).run
        }
    })
}


object Producer extends Producers {
  implicit def FoldableProducer: Foldable[Producer[NoFx, ?]] = new Foldable[Producer[NoFx, ?]] {
    override def foldLeft[A, B](fa: Producer[NoFx, A], b: B)(f: (B, A) => B): B = {
      var s = b
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = f(s, a); Eff.pure(())
        case More(as, next) => s = as.foldLeft(s)(f); s = foldLeft(next, s)(f); Eff.pure(())
      }
      s
    }

    def foldRight[A, B](fa: Producer[NoFx, A], lb: =>B)(f: (A, =>B) => B): B = {
      var s = Need(lb)
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = Need(f(a, s.value)); Eff.pure(())
        case More(as, next) =>
          lazy val ls = Need(as.foldRight(s.value)((a, b) => f(a, b)))
          s = Need(foldRight(next, ls.value)((a, b) => f(a, b)))
          Eff.pure(())
      }
      s.value
    }

    def foldMap[A,B](fa: Producer[NoFx, A])(f: A => B)(implicit F: Monoid[B]): B =
      foldLeft(fa, F.zero)((b: B, a: A) => F.append(b, f(a)))

  }

  implicit def ProducerMonad[R :_safe]: Monad[Producer[R, ?]] = new Monad[Producer[R, ?]] {
    def bind[A, B](fa: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
      fa.flatMap(f)

    def point[A](a: =>A): Producer[R, A] =
      one(a)
  }

}

trait Producers {
  def done[R :_safe, A]: Producer[R, A] =
    Producer[R, A](pure(Done()))

  def one[R :_safe, A](a: A): Producer[R, A] =
    Producer[R, A](pure(One(a)))

  def oneEff[R :_safe, A](e: Eff[R, A]): Producer[R, A] =
    Producer[R, A](e.flatMap(a => one(a).run))

  def oneOrMore[R :_safe, A](a: A, as: List[A]): Producer[R, A] =
    Producer[R, A](pure(More(a +: as, done)))

  def repeat[R :_safe, A](p: Producer[R, A]): Producer[R, A] =
    Producer(p.run flatMap {
      case Done() => pure(Done())
      case One(a) => protect(More(List(a), repeat(p)))
      case More(as, next) => protect(More(as, next append repeat(p)))
    })

  def repeatValue[R :_safe, A](a: A): Producer[R, A] =
    Producer(protect(More(List(a), repeatValue(a))))

  def repeatEval[R :_safe, A](e: Eff[R, A]): Producer[R, A] =
    Producer(e.flatMap(a => (one(a) append repeatEval(e)).run))

  def fill[R :_safe, A](n: Int)(p: Producer[R, A]): Producer[R, A] =
    if (n <= 0) done[R, A]
    else p append fill(n - 1)(p)

  def emit[R :_safe, A](elements: List[A]): Producer[R, A] =
    elements match {
      case Nil      => done[R, A]
      case a :: Nil => one[R, A](a)
      case a :: as  => oneOrMore(a, as)
    }

  def emitSeq[R :_safe, A](elements: Seq[A]): Producer[R, A] =
    elements.headOption match {
      case None    => done[R, A]
      case Some(a) => one[R, A](a) append emitSeq(elements.tail)
    }

  def eval[R :_safe, A](a: Eff[R, A]): Producer[R, A] =
    Producer(a.map(One(_)))

  def emitEff[R :_safe, A](elements: Eff[R, List[A]]): Producer[R, A] =
    Producer(elements flatMap {
      case Nil      => done[R, A].run
      case a :: Nil => one(a).run
      case a :: as  => oneOrMore(a, as).run
    })

  def fold[R :_safe, A, B, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, B]): Eff[R, B] =
    start.flatMap { init =>
      def go(p: Producer[R, A], s: S): Eff[R, S] =
        p.run flatMap {
          case Done() => pure[R, S](s)
          case One(a) => protect[R, S](f(s, a))
          case More(as, next) =>
            val newS = as.foldLeft(s)(f)
            go(next, newS)
        }

      go(producer, init)
    } flatMap end

  def observe[R :_safe, A, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, Unit]): Producer[R, A] =
    Producer[R, A](start flatMap { init =>
      def go(p: Producer[R, A], s: S): Producer[R, A] =
        Producer[R, A] {
          p.run flatMap {
            case Done() => end(s) >> done[R, A].run
            case One(a) => end(s) >> one[R, A](a).run
            case More(as, next) =>
              val newS = as.foldLeft(s)(f)
              (emit(as) append go(next, newS)).run
          }
        }

      go(producer, init).run
    })

  def runLast[R :_safe, A](producer: Producer[R, A]): Eff[R, Option[A]] =
    producer.run flatMap {
      case Done() => pure[R, Option[A]](None)
      case One(a) => pure[R, Option[A]](Option(a))
      case More(as, next) => runLast(next).map(_.orElse(as.lastOption))
    }

  def runList[R :_safe, A](producer: Producer[R, A]): Eff[R, List[A]] =
    producer.fold(pure(Vector[A]()), (vs: Vector[A], a: A) => vs :+ a, (vs:Vector[A]) => pure(vs.toList))

  def collect[R :_safe, A](producer: Producer[R, A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run flatMap {
      case Done() => pure(())
      case One(a) => tell(a)
      case More(as, next) => as.traverse(tell[R, A]) >> collect(next)
    }

  def into[R, U, A](producer: Producer[R, A])(implicit intoPoly: IntoPoly[R, U], s: Safe |= U): Producer[U, A] =
    Producer(producer.run.into[U] flatMap {
      case Done() => done[U, A].run
      case One(a) => one[U, A](a).run
      case More(as, next) => protect(More[U, A](as, into(next)))
    })

  def empty[R :_safe, A]: Producer[R, A] =
    done

  def pipe[R, A, B](p: Producer[R, A], t: Transducer[R, A, B]): Producer[R, B] =
    t(p)

  def filter[R :_safe, A](producer: Producer[R, A])(f: A => Boolean): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(a) => protect[R, A](a).as(if (f(a)) One(a) else Done())
      case More(as, next) =>
        as filter f match {
          case Nil => next.filter(f).run
          case a :: rest => (oneOrMore(a, rest) append next.filter(f)).run
        }
    })

  def flatten[R :_safe, A](producer: Producer[R, Producer[R, A]]): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(p) => p.run
      case More(ps, next) => (flattenProducers(ps) append flatten(next)).run
    })

  def flattenProducers[R :_safe, A](producers: List[Producer[R, A]]): Producer[R, A] =
    producers match {
      case Nil => done
      case p :: rest => p append flattenProducers(rest)
    }

  def flattenSeq[R :_safe, A](producer: Producer[R, Seq[A]]): Producer[R, A] =
    producer.flatMap(as => emitSeq(as.toList))

  /** accumulate chunks of size n inside More nodes */
  def chunk[R :_safe, A](size: Int)(producer: Producer[R, A]): Producer[R, A] = {
    def go(p: Producer[R, A], elements: Vector[A]): Producer[R, A] =
      Producer[R, A](
        p.run flatMap {
          case Done() => emit[R, A](elements.toList).run
          case One(a) => emit[R, A]((elements :+ a).toList).run

          case More(as, next) =>
            val es = elements ++ as
            if (es.size == size) (emit[R, A](es.toList) append go(next, Vector.empty)).run
            else                 go(next, es).run
        })

    go(producer, Vector.empty)
  }

  def sliding[R :_safe, A](size: Int)(producer: Producer[R, A]): Producer[R, List[A]] = {

    def go(p: Producer[R, A], elements: Vector[A]): Producer[R, List[A]] =
      Producer[R, List[A]](
        peek(p).flatMap {
          case (Some(a), as) =>
            val es = elements :+ a
            if (es.size == size) (one(es.toList) append go(as, Vector.empty)).run
            else                 go(as, es).run

          case (None, _) =>
            one(elements.toList).run
        })

    go(producer, Vector.empty)
  }

  def peek[R :_safe, A](producer: Producer[R, A]): Eff[R, (Option[A], Producer[R, A])] =
    producer.run map {
      case Done() => (None, done[R, A])
      case One(a) => (Option(a), done[R, A])
      case More(as, next) => (as.headOption, emit(as.tail) append next)
    }

  def flattenList[R :_safe, A](producer: Producer[R, List[A]]): Producer[R, A] =
    producer.flatMap(emit[R, A])

  def sequence[R :_safe, F[_], A](n: Int)(producer: Producer[R, Eff[R, A]])(implicit f: F |= R) =
    sliding(n)(producer).flatMap { actions => emitEff(Eff.sequenceA(actions)) }

  private[producer] def cata[R :_safe, A, B](producer: Producer[R, A])(onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (List[A], Producer[R, A]) => Producer[R, B]): Producer[R, B] =
    Producer[R, B](producer.run.flatMap {
      case Done() => onDone.run
      case One(a) => onOne(a).run
      case More(as, next) => onMore(as, next).run
    })

}
