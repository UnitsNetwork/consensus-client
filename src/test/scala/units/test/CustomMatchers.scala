package units.test

import scala.reflect.ClassTag

trait CustomMatchers {
  protected def is[T](x: Any)(implicit ct: ClassTag[T]): T = x match {
    case x: T => x
    case x    => throw new RuntimeException(s"Expected an instance of ${ct.runtimeClass.getSimpleName}, got ${x.getClass.getSimpleName}: $x")
  }
}
