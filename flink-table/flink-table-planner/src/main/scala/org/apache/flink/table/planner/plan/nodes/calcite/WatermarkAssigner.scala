/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.nodes.calcite

import org.apache.flink.table.planner.calcite.FlinkTypeFactory

import com.google.common.collect.ImmutableList
import org.apache.calcite.plan.{RelOptCluster, RelOptPredicateList, RelTraitSet}
import org.apache.calcite.rel.`type`.{RelDataType, RelDataTypeFieldImpl}
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.calcite.rel.hint.{Hintable, RelHint}
import org.apache.calcite.rex.{RexNode, RexSimplify}
import org.apache.calcite.sql.`type`.SqlTypeName

import java.util
import java.util.ArrayList

import scala.collection.JavaConversions._

/** Relational operator that generates [[org.apache.flink.streaming.api.watermark.Watermark]]. */
abstract class WatermarkAssigner(
    cluster: RelOptCluster,
    traits: RelTraitSet,
    inputRel: RelNode,
    val hints: util.List[RelHint],
    val rowtimeFieldIndex: Int,
    val watermarkExpr: RexNode)
  extends SingleRel(cluster, traits, inputRel)
  with Hintable {

  override def deriveRowType(): RelDataType = {
    val inputRowType = inputRel.getRowType
    val typeFactory = cluster.getTypeFactory.asInstanceOf[FlinkTypeFactory]

    val newFieldList = inputRowType.getFieldList.map {
      f =>
        if (f.getIndex == rowtimeFieldIndex) {
          val rowtimeIndicatorType = typeFactory.createRowtimeIndicatorType(
            f.getType.isNullable,
            f.getType.getSqlTypeName == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE)
          new RelDataTypeFieldImpl(f.getName, f.getIndex, rowtimeIndicatorType)
        } else {
          f
        }
    }

    val builder = typeFactory.builder
    builder.addAll(newFieldList)
    builder.build()
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    val rowtimeFieldName = inputRel.getRowType.getFieldNames.get(rowtimeFieldIndex)
    super
      .explainTerms(pw)
      .item("rowtime", rowtimeFieldName)
      .item("watermark", watermarkExpr.toString)
  }

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    copy(traitSet, inputs.get(0), hints, rowtimeFieldIndex, watermarkExpr)
  }

  /** Copies a new WatermarkAssigner. */
  def copy(
      traitSet: RelTraitSet,
      input: RelNode,
      hints: util.List[RelHint],
      rowtime: Int,
      watermark: RexNode): RelNode

  override def getHints: ImmutableList[RelHint] = {
    val arrayHints = hints.toArray(new Array[RelHint](0))
    ImmutableList.copyOf(arrayHints)
  }

  def withHints(hintList: util.List[RelHint]): RelNode
}

object WatermarkUtils {
  def simplify(cluster: RelOptCluster, watermarkExpr: RexNode): RexNode = {
    new RexSimplify(
      cluster.getRexBuilder,
      RelOptPredicateList.EMPTY,
      cluster.getPlanner.getExecutor).simplify(watermarkExpr)
  }
}
