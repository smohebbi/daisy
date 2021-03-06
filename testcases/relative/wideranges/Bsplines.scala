
import daisy.lang._
import Real._

object Bsplines {

  def bspline0(u: Real): Real = {
    require(0 <= u && u <= 0.875)
    (1 - u) * (1 - u) * (1 - u) / 6.0
  } ensuring (res => 0 <= res && res <= 0.17 && res +/- 1e-15)
  // proven in paper: [-0.05, 0.17]

  def bspline1(u: Real): Real = {
    require(0.875 <= u && u <= 1)
    (3 * u*u*u - 6 * u*u + 4) / 6.0
  } ensuring (res => 0.16 <= res && res <= 0.7 && res +/- 1e-15)
  // in paper [-0.05, 0.98]

  def bspline2(u: Real): Real = {
    require(0.5 <= u && u <= 1)
    (-3 * u*u*u  + 3*u*u + 3*u + 1) / 6.0
  } ensuring (res => 0.16 <= res && res <= 0.7 && res +/- 1e-15)
  // in paper [-0.02, 0.89]

  def bspline3(u: Real): Real = {
    require(0.125 <= u && u <= 10)
    -u*u*u / 6.0
  } ensuring (res => -0.17 <= res && res <= 0.0 && res +/- 1e-15)
  // in paper [-0.17, 0.05]

}
