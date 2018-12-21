/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ir.v4_0

import org.neo4j.cypher.internal.ir.v4_0.InterestingOrder.{Asc, ColumnOrder, Desc}
import org.neo4j.cypher.internal.v4_0.expressions._

object InterestingOrder {

  sealed trait ColumnOrder {
    def id: String

    def expression: Expression

    def projected(newExpression: Expression, newProjections: Map[String, Expression]): ColumnOrder

    def projections: Map[String, Expression]
  }

  case class Asc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty) extends ColumnOrder {
    override def projected(newExpression: Expression, newProjections: Map[String, Expression]): ColumnOrder = Asc(id, newExpression, newProjections)
  }

  case class Desc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty) extends ColumnOrder {
    override def projected(newExpression: Expression, newProjections: Map[String, Expression]): ColumnOrder = Desc(id, newExpression, newProjections)
  }

  val empty = InterestingOrder(RequiredOrderCandidate.empty, Seq.empty)

  def required(candidate: RequiredOrderCandidate): InterestingOrder = InterestingOrder(candidate, Seq.empty)

  def interested(candidate: InterestingOrderCandidate): InterestingOrder = InterestingOrder(RequiredOrderCandidate.empty, Seq(candidate))
}

/**
  * A single PlannerQuery can require an ordering on its results. This ordering can emerge
  * from an ORDER BY, or even from distinct or aggregation. The requirement on the ordering can
  * be strict (it needs to be ordered for correct results) or weak (ordering will allow for optimizations).
  * A weak requirement might in addition not care about column order and sort direction.
  *
  * Right now this type only encodes strict requirements that emerged because of an ORDER BY.
  *
  * @param requiredOrderCandidate     a candidate for required sort directions of columns
  * @param interestingOrderCandidates a sequence of candidates for interesting sort directions of columns
  */
case class InterestingOrder(requiredOrderCandidate: RequiredOrderCandidate,
                            interestingOrderCandidates: Seq[InterestingOrderCandidate] = Seq.empty) {

  import InterestingOrder._

  val isEmpty: Boolean = requiredOrderCandidate.isEmpty && interestingOrderCandidates.forall(_.isEmpty)

  // TODO maybe merge some candidates
  def interested(candidate: InterestingOrderCandidate): InterestingOrder = InterestingOrder(requiredOrderCandidate, interestingOrderCandidates :+ candidate)

  // TODO maybe merge some candidates
  def asInteresting: InterestingOrder =
    if (requiredOrderCandidate.isEmpty) this
    else InterestingOrder(RequiredOrderCandidate.empty,
      interestingOrderCandidates :+ requiredOrderCandidate.asInteresting)

  def withReverseProjectedColumns(projectExpressions: Map[String, Expression], argumentIds: Set[String]): InterestingOrder = {
    def columnIfArgument(expression: Expression, column: ColumnOrder): Option[ColumnOrder] = {
      if (argumentIds.contains(expression.asCanonicalStringVal)) Some(column) else None
    }

    def rename(columns: Seq[ColumnOrder]): Seq[ColumnOrder] = {
      columns.flatMap { column: ColumnOrder =>
        // expression with all incoming projections applied
        val projected = projectExpression(column.expression, column.projections)
        projected match {
          case Property(Variable(prevVarName), _) if projectExpressions.contains(prevVarName) =>
            Some(column.projected(projected, Map(prevVarName -> projectExpressions(prevVarName))))
          case Variable(prevVarName) if projectExpressions.contains(prevVarName) =>
            Some(column.projected(projected, Map(prevVarName -> projectExpressions(prevVarName))))
          case _ =>
            columnIfArgument(projected, column)
        }
      }

    }

    InterestingOrder(requiredOrderCandidate.renameColumns(rename),
      interestingOrderCandidates.map(_.renameColumns(rename)).filter(!_.isEmpty))
  }

  private def projectExpression(expression: Expression, projections: Map[String, Expression]): Expression = {
    expression match {
      case Variable(varName) =>
        projections.getOrElse(varName, expression)

      case Property(Variable(varName), propertyKeyName) =>
        if (projections.contains(expression.asCanonicalStringVal))
          projections(expression.asCanonicalStringVal)
        else if (projections.contains(varName))
          Property(projections(varName), propertyKeyName)(expression.position)
        else
          expression

      // TODO handle generic case e.g Add(a, b)
      case _ => expression
    }
  }

  /**
    * Checks if a RequiredOrder is satisfied by a ProvidedOrder
    */
  def satisfiedBy(providedOrder: ProvidedOrder): Boolean = {
    def satisfied(providedId: String, requiredOrder: Expression, projections: Map[String, Expression]): Boolean = {
      val projected = projectExpression(requiredOrder, projections)
      if (providedId == requiredOrder.asCanonicalStringVal || providedId == projected.asCanonicalStringVal)
        true
      else if (projected != requiredOrder) {
        satisfied(providedId, projected, projections)
      }
      else
        false
    }
    requiredOrderCandidate.order.zipAll(providedOrder.columns, null, null).forall {
      case (null, _) => true // no required order left
      case (_, null) => false // required order left but no provided
      case (InterestingOrder.Asc(_, e, projections), ProvidedOrder.Asc(providedId)) => satisfied(providedId, e, projections)
      case (InterestingOrder.Desc(_, e, projections), ProvidedOrder.Desc(providedId)) => satisfied(providedId, e, projections)
      case _ => false
    }
  }
}

// TODO put this somewhere else
// remove the import InterestingOrder.{Asc, ColumnOrder, Desc} since it is only for this part
trait OrderCandidate {
  def order: Seq[ColumnOrder]

  def isEmpty: Boolean = order.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def headOption: Option[ColumnOrder] = order.headOption

  def renameColumns(f: Seq[ColumnOrder] => Seq[ColumnOrder]): OrderCandidate

  def asc(id: String, expression: Expression, projections: Map[String, Expression]): OrderCandidate

  def desc(id: String, expression: Expression, projections: Map[String, Expression]): OrderCandidate
}

case class RequiredOrderCandidate(order: Seq[ColumnOrder]) extends OrderCandidate {
  def asInteresting: InterestingOrderCandidate = InterestingOrderCandidate(order)

  override def renameColumns(f: Seq[ColumnOrder] => Seq[ColumnOrder]): RequiredOrderCandidate = RequiredOrderCandidate(f(order))

  override def asc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): RequiredOrderCandidate = RequiredOrderCandidate(order :+ Asc(id, expression, projections))

  override def desc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): RequiredOrderCandidate = RequiredOrderCandidate(order :+ Desc(id, expression, projections))
}

object RequiredOrderCandidate {
  def empty: RequiredOrderCandidate = RequiredOrderCandidate(Seq.empty)

  def asc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): RequiredOrderCandidate = empty.asc(id, expression, projections)

  def desc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): RequiredOrderCandidate = empty.desc(id, expression, projections)
}

case class InterestingOrderCandidate(order: Seq[ColumnOrder]) extends OrderCandidate {
  override def renameColumns(f: Seq[ColumnOrder] => Seq[ColumnOrder]): InterestingOrderCandidate = InterestingOrderCandidate(f(order))

  override def asc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): InterestingOrderCandidate = InterestingOrderCandidate(order :+ Asc(id, expression, projections))

  override def desc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): InterestingOrderCandidate = InterestingOrderCandidate(order :+ Desc(id, expression, projections))
}

object InterestingOrderCandidate {
  def empty: InterestingOrderCandidate = InterestingOrderCandidate(Seq.empty)

  def asc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): InterestingOrderCandidate = empty.asc(id, expression, projections)

  def desc(id: String, expression: Expression, projections: Map[String, Expression] = Map.empty): InterestingOrderCandidate = empty.desc(id, expression, projections)
}

/**
  * Split the id into varName and propName, if the
  * ordered column is a property lookup.
  */
object StringPropertyLookup {

  def unapply(arg: InterestingOrder.ColumnOrder): Option[(String, String)] = {
    arg.id.split("\\.", 2) match {
      case Array(varName, propName) => Some((varName, propName))
      case _ => None
    }
  }

  def unapply(arg: String): Option[(String, String)] = {
    arg.split("\\.", 2) match {
      case Array(varName, propName) => Some((varName, propName))
      case _ => None
    }
  }
}