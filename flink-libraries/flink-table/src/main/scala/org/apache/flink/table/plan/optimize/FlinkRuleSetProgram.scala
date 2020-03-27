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

package org.apache.flink.table.plan.optimize

import org.apache.calcite.plan.{RelOptRule, RelTrait}
import org.apache.calcite.tools.RuleSet
import org.apache.flink.util.Preconditions

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * A FlinkOptimizeProgram that transforms a relational expression into
  * another relational expression with [[RuleSet]].
  */
abstract class FlinkRuleSetProgram[OC <: OptimizeContext] extends FlinkOptimizeProgram[OC] {

  protected val rules = new mutable.ArrayBuffer[RelOptRule]()
  protected var targetTraits = Array.empty[RelTrait]

  /**
    * Adds specified rules to this program.
    */
  def add(ruleSet: RuleSet): Unit = {
    Preconditions.checkNotNull(ruleSet)
    ruleSet.asScala.foreach { r =>
      if (!contains(r)) {
        rules += r
      }
    }
  }

  /**
    * Removes specified rules from this program.
    */
  def remove(ruleSet: RuleSet): Unit = {
    Preconditions.checkNotNull(ruleSet)
    ruleSet.asScala.foreach { r =>
      val index = rules.indexOf(r)
      if (index >= 0) {
        rules.remove(index)
      }
    }
  }

  /**
    * Removes all rules from this program first, and then adds specified rules to this program.
    */
  def replaceAll(ruleSet: RuleSet): Unit = {
    Preconditions.checkNotNull(ruleSet)
    rules.clear()
    ruleSet.asScala.foreach { r =>
      rules += r
    }
  }

  /**
    * Checks whether this program contains the specified rule.
    */
  def contains(rule: RelOptRule): Boolean = {
    Preconditions.checkNotNull(rule)
    rules.contains(rule)
  }

  /**
    * Sets target traits that the optimized relational expression should contain them.
    */
  def setTargetTraits(relTraits: Array[RelTrait]): Unit = {
    if (relTraits != null) {
      targetTraits = relTraits
    } else {
      targetTraits = Array.empty[RelTrait]
    }
  }
}
