/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.cypher.internal.compiler.v2_2.ast.rewriters

import org.neo4j.cypher.internal.compiler.v2_2._
import org.neo4j.cypher.internal.compiler.v2_2.ast._

case object rewriteEqualityToInCollection extends Rewriter {
  override def apply(that: AnyRef) = bottomUp(instance).apply(that)

  private val instance: Rewriter = Rewriter.lift {
    // id(a) = value
    case predicate@Equals(func@FunctionInvocation(_, _, IndexedSeq(idExpr)), p@ConstantExpression(idValueExpr))
      if func.function == Some(functions.Id) =>

      In(func, Collection(Seq(idValueExpr))(p.position))(predicate.position)
    // a.prop = value
    case predicate@Equals(prop@Property(id: Identifier, propKeyName), p@ConstantExpression(idValueExpr)) =>

      In(prop, Collection(Seq(idValueExpr))(p.position))(predicate.position)
  }
}
