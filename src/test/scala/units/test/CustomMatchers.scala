package units.test

import org.scalatest.TestSuite

import scala.reflect.ClassTag

trait CustomMatchers { this: TestSuite =>
  protected def is[T](x: Any)(implicit ct: ClassTag[T]): T = x match {
    case x: T => x
    case x    => fail(s"Expected an instance of ${ct.runtimeClass.getSimpleName}, got ${x.getClass.getSimpleName}: $x")
  }
}
