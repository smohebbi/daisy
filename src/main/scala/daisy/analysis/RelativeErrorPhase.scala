// Copyright 2017 MPI-SWS, Saarbruecken, Germany

package daisy
package analysis

import lang.Trees._
import lang.Constructors._

import lang.Identifiers.{Identifier}
import lang.Types.RealType
import lang.TreeOps._

import tools.{SMTRange, Evaluators, Interval, Rational, AffineForm, DivisionByZeroException}
import Rational._
import tools.FinitePrecision._
import Interval._
import daisy.utils.CachingMap

import solvers.{Solver, Z3Solver}
import smtlib.parser.Commands.{AttributeOption, SetOption}
import smtlib.parser.Terms.{Attribute, SKeyword, SNumeral, SSymbol}

import scala.collection.immutable.Map
import scala.collection.parallel.{ParSeq, ParSet}
import scala.util.control.Breaks._


/**
 * Compute relative errors directly, i.e. not through first computing
 * absolute errors.
 *
 * Uses the (1 + delta) abstraction for floating-point computations.
 *
 *
 * Prerequisites:
 * - SpecsProcessingPhase
 */
object RelativeErrorPhase extends PhaseComponent {
  override val name = "Relative Error"
  override val description = "Computes relative errors directly."
  override val definedOptions: Set[CmdLineOption[Any]] = Set(
    StringChoiceOption(
      "rel-rangeMethod",
      Set("affine", "interval", "smtreuse", "smtredo", "smtcomplete"),
      "interval",
      "Method to use for range analysis"),
    NumOption(
      "rel-divLimit",
      3,
      "Max amount of interval divisions"),
    NumOption(
      "rel-divRemainder",
      0,
      "Max amount of interval divisions for remainder term"),
    NumOption(
      "rel-totalOpt",
      32,
      "Max total amount of analysis runs"),
    //    StringChoiceOption(
    //      "rel-subdiv",
    //      Set("simple", "model"),
    //      "simple",
    //      "Method to subdivide intervals"),
    StringChoiceOption(
      "approach",
      Set("taylor", "naive"),
      "taylor",
      "Approach for expressions")
  )
  override def apply(cfg: Config) = new RelativeErrorPhase(cfg, name, "relError")
}

class RelativeErrorPhase(val cfg: Config, val name: String, val shortName: String) extends DaisyPhase
    with tools.Taylor with tools.Subdivision with tools.RoundoffEvaluators {
  implicit val debugSection = DebugSectionAnalysis

  // default parameters for the complete run
  val divLimit: Int = cfg.option[Long]("rel-divLimit").toInt
  val divRemainder: Int = cfg.option[Long]("rel-divRemainder").toInt
  val rangeMethod: String = cfg.option[String]("rel-rangeMethod")
  // val subdiv: String = cfg.option[String]("rel-subdiv")
  val approach: String = cfg.option[String]("approach")
  val uniformPrecision: Precision = cfg.option[Precision]("precision")

  override def run(ctx: Context, prg: Program): (Context, Program) = {
    startRun()

    for (fnc <- functionsToConsider(prg)){

      cfg.reporter.info("Evaluating " + fnc.id + "...")
      val bodyReal = fnc.body.get
      val deltaVarMap = mapDeltasToVars(bodyReal)
      val epsVarMap = mapEpsilonsToVars(bodyReal)
      val bodyDeltaAbs = if (denormals) {
        deltaAbstract(bodyReal, deltaVarMap, epsVarMap)._1
      } else {
        deltaAbstract(bodyReal, deltaVarMap, Map.empty)._1
      }
      // cfg.reporter.warning(s"bodyDelta $bodyDeltaAbs")
      // Step 1: disregard initial errors for now
      // (f(x) - fl(x))/ f(x)

      val relErrorExpr = Division(Minus(bodyReal, bodyDeltaAbs), bodyReal)

      cfg.reporter.info("\n" + fnc.id + ", bodyReal: " + bodyReal)

      val startTime = System.currentTimeMillis

      // adding constraints on deltas here
      val deltas = deltasOf(relErrorExpr)
      var deltaIntervalMap: Map[Identifier, Interval] =
        deltas.map(delta => (delta.id -> deltaIntervalFloat64)).toMap

      val eps = epsilonsOf(relErrorExpr)
      deltaIntervalMap = deltaIntervalMap ++ eps.map(e => (e.id -> epsilonIntervalFloat64))

      val inputValMap: Map[Identifier, Interval] = ctx.specInputRanges(fnc.id) ++ deltaIntervalMap

      // no initial errors
      val allIDs = fnc.params.map(_.id)
      val inputErrorMap: Map[Identifier, Rational] =
        allIDs.map(id => (id -> uniformPrecision.absRoundoff(inputValMap(id)))).toMap

      try {
        val (relError, tmpList) = approach match {
          case "taylor" => getRelErrorTaylorApprox(relErrorExpr, inputValMap, bodyReal)
          case "naive" => getRelErrorNaive(relErrorExpr, inputValMap, bodyReal)
        }
        cfg.reporter.warning("Failed on " + tmpList.distinct.size + " sub-domain(s)")

        val list = mergeIntervals(tmpList, inputValMap)

        // if returned None or too many subintervals where failed to compute relError,
        // say it is not possible to compute
        if (relError.isDefined && (list.size < 30)) {
          val time = System.currentTimeMillis
          cfg.reporter.info("relError: " + relError.get.toString + ", time: " + (time - startTime))
          if (list.nonEmpty) {
            cfg.reporter.info("On several sub-intervals relative error cannot be computed.")
            cfg.reporter.info("Computing absolute error on these sub-intervals.")
            for (mapEntry <- list) {
              // here we compute the abs error for intervals where rel error is not possible
              val absError = getAbsError(bodyReal, mapEntry, inputErrorMap, uniformPrecision)
              cfg.reporter.info(s"For intervals $mapEntry, absError: $absError, time: " +
                (System.currentTimeMillis - time))
            }
          }
        } else {
          cfg.reporter.info("Not possible to get relative error, compute the absolute instead, time:" +
            (System.currentTimeMillis - startTime))
          val time = System.currentTimeMillis
          // fixme for JetEngine DivByZeroException is thrown
          val absError = getAbsError(bodyReal, inputValMap, inputErrorMap, uniformPrecision)
          cfg.reporter.info(s"absError: $absError, time: " +
            (System.currentTimeMillis - time))
        }
      }
      catch {
        case e: Throwable => {
          cfg.reporter.info("Something went wrong while computing the relative error.")
          cfg.reporter.info(e.printStackTrace())}
      }

    }
    finishRun(ctx, prg)
  }

  /**
   * Evaluates the relative error for the taylor approximation for relErrorExpr on the set of subintervals
   * @param relErrorExpr - expression to evaluate, i.e. |f(x) - f~(x)|/f(x)
   * @param inputValMap - input ranges for variables plus deltas
   * @return
   */
  private def getRelErrorTaylorApprox(relErrorExpr: Expr, inputValMap: Map[Identifier, Interval],
    bodyReal: Expr): (Option[Rational], Seq[Map[Identifier, Interval]]) = {

    var listFailInterval: Seq[Map[Identifier, Interval]] = Seq.empty
    var finalErr: Option[Rational] = None

    // get intervals subdivision for the complete divLimit
    // val newSet = getSubintervals(inputValMap, bodyReal, ctx, subdiv, divLimit)
    val newSet = getEqualSubintervals(inputValMap, divLimit)

    cfg.reporter.ifDebug { debug =>
      cfg.reporter.debug(s"EXPRESSION is $relErrorExpr")
      cfg.reporter.debug("The set we got")

      // output subintervals without deltas
      for (entry <- newSet){
        cfg.reporter.debug("============================================")
        for (mapEntry <- entry if !(mapEntry._1.isDeltaId|| mapEntry._1.isEpsilonId)) {
          cfg.reporter.debug(mapEntry._1 + " -> " + mapEntry._2)
        }
      }
      cfg.reporter.debug(s"We need to evaluate expression on " + newSet.size + " intervals")
      cfg.reporter.debug("there are " + deltasOf(relErrorExpr).size + " deltas")
    }

    val taylorFirst = getDerivative(relErrorExpr)

    // cfg.reporter.warning(s"the taylor expression we got is ")
    // taylorFirst.foreach(x=>{cfg.reporter.debug(s"term is $x")})
    cfg.reporter.info("Computing the error ...")

    cfg.reporter.info(s"subdiv for remainder $divRemainder")
    // separate timer for remainder
    val remainderTime = System.currentTimeMillis
    val remainderMap = getEqualSubintervals(inputValMap, divLimit, divRemainder)
    val taylorRemainder = getTaylorRemainder(relErrorExpr, remainderMap)
    cfg.reporter.info(s"The taylor remainder value is $taylorRemainder, time: " +
      (System.currentTimeMillis - remainderTime))
    if (taylorRemainder.isDefined) {
      val errForSum = taylorFirst.map(x => {
        val (expr, wrt) = x
        val tmpExpr = moreSimplify(Times(replaceDeltasWithZeros(expr), Delta(wrt)))
        cfg.reporter.debug(s"Evaluate the term $tmpExpr")
        // do not call evaluation function on all subintervals
        // if simplified expression is delta or RealLiteral
        val tmpForMax = tmpExpr match {
          case x @ Variable(id) => List(evaluateOpt(tmpExpr, inputValMap, rangeMethod))
          case x @ RealLiteral(r) => List(evaluateOpt(tmpExpr, inputValMap, rangeMethod))
          case _ => newSet.map(interval => {
            val tmp = evaluateOpt(tmpExpr, interval, rangeMethod)
            cfg.reporter.debug("err on " + removeDeltasFromMap(interval) + s" is $tmp")
            if (tmp.isEmpty && !listFailInterval.contains(interval) && !listFailed.contains(interval)) {
              listFailInterval = listFailInterval :+ interval
            }
            tmp
          })
        }
        tmpForMax.max(optionAbsOrdering)
      })
      cfg.reporter.debug(s"we need to sum $errForSum")

      errForSum.foreach(x => {
        if (finalErr.isDefined) {
          finalErr = Some(finalErr.get + x.getOrElse(Rational.zero))
        } else {
          finalErr = x
        }
      })
      if (finalErr.isDefined) {
        // TODO: why is this getOrElse?
        finalErr = Some(finalErr.get + taylorRemainder.getOrElse(Rational.zero))
      }
    }

    listFailInterval = (listFailInterval ++ listFailed).toSet.toList
    cfg.reporter.debug("print what is ACTUALLY in ListFailed " +
      listFailed.map(removeDeltasFromMap).map(_.keySet.map(_.globalId)))
    (finalErr, listFailInterval)
  }

  /**
   * Evaluates the relative error for the original relErrorExpr on the set of subintervals
   * @param relErrorExpr - expression to evaluate, i.e. |f(x) - f~(x)|/f(x)
   * @param inputValMap - input ranges for variables plus deltas
   * @return
   */
  private def getRelErrorNaive(relErrorExpr: Expr, inputValMap: Map[Identifier, Interval],
    bodyReal: Expr): (Option[Rational], Seq[Map[Identifier, Interval]]) = {

    var listFailInterval: Seq[Map[Identifier, Interval]] = Seq.empty

    // get intervals subdivision for the complete divLimit
    val newSet = getEqualSubintervals(inputValMap, divLimit).par

    cfg.reporter.ifDebug { debug =>
      cfg.reporter.debug("The set we got")
      // output subintervals without deltas
      for (entry <- newSet) {
        for (mapEntry <- entry if !(mapEntry._1.isDeltaId|| mapEntry._1.isEpsilonId))
          cfg.reporter.debug(mapEntry._1 + " -> " + mapEntry._2)
      }
    }

    cfg.reporter.info("Computing the error ...")
    val errors = newSet.map(x => {
      val tmp = evaluateOpt(relErrorExpr, x, rangeMethod)
      if (tmp.isEmpty) listFailInterval = listFailInterval :+ x
      tmp
    })
    cfg.reporter.debug(errors)
    (errors.max, listFailInterval)
  }

  private def evaluateOpt(relErrorExpr: Expr, inputValMap: Map[Identifier, Interval],
    rangeMethod: String): Option[Rational] = {
    try {
      rangeMethod match {
        case ("interval") =>
          Some(maxAbs(Evaluators.evalInterval(relErrorExpr, inputValMap)))

        case ("affine") =>
          Some(maxAbs(Evaluators.evalAffine(relErrorExpr,
            inputValMap.map(x => (x._1 -> AffineForm(x._2)))).toInterval))

        case ("smtreuse") =>
          Some(maxAbs(evaluateSMTReuse(relErrorExpr,
            inputValMap.map({ case (id, int) => (id -> SMTRange(Variable(id), int)) })).toInterval))

        case ("smtredo") =>
          Some(maxAbs(Evaluators.evalSMT(relErrorExpr,
            inputValMap.map({ case (id, int) => (id -> SMTRange(Variable(id), int)) })).toInterval))

        case("smtcomplete") =>
          Some(maxAbs(evaluateSMTComplete(relErrorExpr, inputValMap).toInterval))

        // case _ => cfg.reporter.error("Something went wrong. Unknown range method")
      }
    }
    catch{
      case z0: DivisionByZeroException => None
    }
  }

  private def getAbsError(bodyReal: Expr, inputValMap: Map[Identifier, Interval],
    inputErrorMap: Map[Identifier, Rational], uniformPrecision: Precision): Rational = rangeMethod match {
    case "interval" =>
      uniformRoundoff_IA_AA(bodyReal, inputValMap, inputErrorMap, uniformPrecision,
        trackRoundoffErrors = true)._1

    case "affine" =>
      uniformRoundoff_AA_AA(bodyReal, inputValMap, inputErrorMap, uniformPrecision,
        trackRoundoffErrors = true)._1

    case "smtreuse" | "smtredo" | "smtcomplete" =>
      uniformRoundoff_SMT_AA(bodyReal, inputValMap, inputErrorMap, uniformPrecision,
        trackRoundoffErrors = true)._1

    case _ =>
      cfg.reporter.fatalError(s"Range method $rangeMethod is not supported.")
  }

  val precisionLower = Rational.fromReal(0.01)
  val precisionDefault = Rational.fromReal(0.000000000000000001)
  val loopDefault = 100
  val loopLower = 75


  def evaluateSMTComplete(expr: Expr, _intMap: Map[Identifier, Interval]): SMTRange = {

    val intMap = _intMap
    val interval = Evaluators.evalInterval(expr, intMap)
    var constrs: Set[Expr] = Set.empty
    val deltas = deltasOf(expr)
    val eps = epsilonsOf(expr)
    val vars = freeVariablesOf(expr)
    intMap.foreach(x => {
      val (id, interval) = x
      if (deltas.contains(Delta(id)) || vars.contains(id) || eps.contains(Epsilon(id))) {
        constrs = constrs ++ SMTRange.toConstraints(Variable(id), interval)
      }
    })
    SMTRange(expr, interval, constrs)
  }

  /**
   * This version records the already seen intervals (from identical, repeated subtrees)
   * and does not recompute the range.
   */
  def evaluateSMTReuse(expr: Expr, _intMap: collection.immutable.Map[Identifier, SMTRange] = Map.empty): SMTRange = {

    // TODO check whether the expr is the best solution here
    val smtRangeMap: collection.mutable.Map[Expr, SMTRange] = new CachingMap[Expr, SMTRange]
    for ((id, smtrange) <- _intMap) {
      smtRangeMap.put(Variable(id), smtrange)
    }

    def evalSMT(e: Expr): SMTRange = smtRangeMap.getOrElse(e, e match {

      case RealLiteral(r) => SMTRange(r)

      case Plus(lhs, rhs) => evalSMT(lhs) + (evalSMT(rhs), precisionDefault, loopLower)

      case Minus(lhs, rhs) => evalSMT(lhs) - (evalSMT(rhs), precisionDefault, loopLower)

      case Times(lhs, rhs) => evalSMT(lhs) * (evalSMT(rhs), precisionDefault, loopLower)

      case Division(lhs, rhs) => evalSMT(lhs) / (evalSMT(rhs), precisionDefault, loopLower)

      case Pow(lhs, rhs) => evalSMT(lhs) ^ (evalSMT(rhs), precisionDefault, loopLower)

      case UMinus(t) => - evalSMT(t)

      case Sqrt(t) => evalSMT(t).squareRoot(precisionDefault, loopDefault)

      case Let(id, value, body) => {
          val smtRange = evalSMT(value)
          smtRangeMap += (Variable(id) -> smtRange)
          evalSMT(body)
        }

      case _ =>
        throw new IllegalArgumentException("Unknown expression. Evaluation failed")
    })
    evalSMT(expr)
  }

  def compareMaps(first: Map[Identifier, Interval], second: Map[Identifier, Interval]): Boolean = {
    // if less than yes

    def compareIntervals(x: Interval, y: Interval): Int = {
      // [0; 1] and [1; 2]
      if (x.xlo < y.xlo) {
        -1
      // [0; 1] and [-1; 0]
      } else if (x.xlo > y.xlo) {
        1
      // [0; 1] and [0; 2]
      } else if (x.xhi < y.xhi) {
        -1
      // [0; 1] and [0; 0.5]
      } else if (x.xhi > y.xhi) {
        1
      // equal
      } else {
        0
      }
    }

    var tmp = 0
    val iterator = first.iterator

    // TODO: this can be probably done nicer
    while (tmp == 0 && iterator.hasNext) {
      val (id, interval) = iterator.next()
      if (!(id.isDeltaId|| id.isEpsilonId)) {
        tmp = compareIntervals(interval, second(id))
      }
    }
    tmp <= 0
  }


  private def mergeIntervals(listFailIntervals: Seq[Map[Identifier, Interval]],
    inputMap: Map[Identifier, Interval]): Seq[Map[Identifier, Interval]] = {

    if (listFailIntervals.nonEmpty) {
      // sort the maps
      val tmpList = listFailIntervals.map(removeDeltasFromMap).sortWith(compareMaps)
      cfg.reporter.debug("===== sorted maps ======")
      tmpList.foreach(x => {cfg.reporter.debug(s"map: $x")})

      // merge all the possible maps in the list
      var ready = false
      var tmpMerged = tmpList
      while (!ready) {
        // merge maps
        val tmpSrc = mergeSortedMaps(tmpMerged)
        // check if maps have changed after merging
        ready = tmpMerged.equals(tmpSrc)
        tmpMerged = tmpSrc
      }
      cfg.reporter.debug("===== merged ======")
      tmpMerged.foreach(x => {cfg.reporter.debug(s"map: $x")})
      tmpMerged

    } else {
      Seq()
    }
  }

  def mergeSortedMaps(in: Seq[Map[Identifier,Interval]]): Seq[Map[Identifier,Interval]] = in match {
    case x :: y :: tail =>
      val merged = mergeMaps(x, y)
      if (merged.size > 1) {
        x +: mergeSortedMaps(y +: tail)
      } else {
        merged ++ mergeSortedMaps(tail)
      }
    case x :: Nil => Seq(x)
    case Nil => Seq()
  }

  def mergeMaps(x: Map[Identifier,Interval], y: Map[Identifier,Interval]): Seq[Map[Identifier,Interval]] = {

    var abort: Boolean = false
    var res: Map[Identifier, Interval] = Map.empty
    breakable(
    for((id, int) <- x){
      (int, y(id)) match {
        case (a, b) if a.equals(b) =>
          res = res + (id -> a)
        case (a, b) if a.xhi == b.xlo =>
          res = res + (id -> Interval(a.xlo, b.xhi))
        //  // TODO does it over-approximate too much?
        // case (a, b) if (a.xlo >= b.xlo) && (a.xhi <= b.xhi) =>
        //  // a is contained in b
        //  res = res + (id -> b)
        // case (a, b) if (b.xlo >= a.xlo) && (b.xhi <= a.xhi) =>
        //  // b is contained in a
        //  res = res + (id -> a)
        case _ =>
          abort = true
          break
      }
    })
    if (abort) {
      // if at least one element of the map cannot be merged,
      // return original maps
      Seq(x, y)
    } else {
      Seq(res)
    }
  }

}
