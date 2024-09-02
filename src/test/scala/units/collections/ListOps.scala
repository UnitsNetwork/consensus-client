package units.collections

import cats.syntax.option.*

object ListOps {
  final implicit class Implicits[A](private val self: List[A]) extends AnyVal {
    def withoutFirst(f: A => Boolean): (Option[A], List[A]) = {
      var found = none[A]
      val xs = self.flatMap { x =>
        found match {
          case None if f(x) =>
            found = x.some
            none
          case _ => x.some
        }
      }
      (found, xs)
    }
  }
}
