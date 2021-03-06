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

package org.apache.flink.runtime.executiongraph.failover;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.runtime.executiongraph.Execution;
import org.apache.flink.runtime.executiongraph.ExecutionEdge;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.IntermediateResult;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.io.network.partition.DataConsumptionException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.FlinkRuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A failover strategy that restarts regions of the ExecutionGraph. A region is defined
 * by this strategy as the weakly connected component of tasks that communicate via pipelined
 * data exchange.
 */
public class RestartPipelinedRegionStrategy extends FailoverStrategy {

	/** The log object used for debugging. */
	private static final Logger LOG = LoggerFactory.getLogger(RestartPipelinedRegionStrategy.class);

	/** The execution graph on which this FailoverStrategy works. */
	private final ExecutionGraph executionGraph;

	/** The executor used for future actions. */
	private final Executor executor;

	/** Fast lookup from vertex to failover region. */
	private final HashMap<ExecutionVertex, FailoverRegion> vertexToRegion;

	/** The max number a region can fail. */
	private int regionFailLimit;

	/**
	 * Creates a new failover strategy to restart pipelined regions that works on the given
	 * execution graph and uses the execution graph's future executor to call restart actions.
	 *
	 * @param executionGraph The execution graph on which this FailoverStrategy will work
	 * @param regionFailLimit The max number a region can fail
	 */
	public RestartPipelinedRegionStrategy(ExecutionGraph executionGraph, int regionFailLimit) {
		this(executionGraph, executionGraph.getFutureExecutor(), regionFailLimit);
	}

	/**
	 * Creates a new failover strategy to restart pipelined regions that works on the given
	 * execution graph and uses the given executor to call restart actions.
	 * 
	 * @param executionGraph The execution graph on which this FailoverStrategy will work
	 * @param executor  The executor used for future actions
	 * @param regionFailLimit The max number a region can fail
	 */
	public RestartPipelinedRegionStrategy(ExecutionGraph executionGraph, Executor executor, int regionFailLimit) {
		this.executionGraph = checkNotNull(executionGraph);
		this.executor = checkNotNull(executor);
		this.vertexToRegion = new HashMap<>();
		this.regionFailLimit = regionFailLimit;
	}

	// ------------------------------------------------------------------------
	//  failover implementation
	// ------------------------------------------------------------------------ 

	@Override
	public void onTaskFailure(Execution taskExecution, Throwable cause) {

		final ExecutionVertex ev = taskExecution.getVertex();
		final FailoverRegion failoverRegion = vertexToRegion.get(ev);
		if (failoverRegion == null) {
			executionGraph.failGlobal(new FlinkException(
				"Can not find a failover region for the execution " + ev.getTaskNameWithSubtaskIndex(), cause));
			return;
		}

		// if it's DataConsumptionException, the producer need to rerun.
		Optional<DataConsumptionException> dataConsumptionException =
			ExceptionUtils.findThrowable(cause, DataConsumptionException.class);
		if (dataConsumptionException.isPresent()) {
			ResultPartitionID predecessorResultPartition = dataConsumptionException.get().getResultPartitionId();

			Execution producer = executionGraph.getRegisteredExecutions().get(predecessorResultPartition.getProducerId());
			if (producer == null) {
				// If the producer has finished, it is removed from registeredExecutions and we need to locate it via the
				// ResultPartitionID and the down-stream task.
				for (IntermediateResult intermediateResult : ev.getJobVertex().getInputs()) {
					IntermediateResultPartition resultPartition = intermediateResult.getPartitionOrNullById(
						predecessorResultPartition.getPartitionId());
					if (resultPartition != null) {
						Execution producerVertexCurrentAttempt = resultPartition.getProducer().getCurrentExecutionAttempt();
						if (producerVertexCurrentAttempt.getAttemptId().equals(predecessorResultPartition.getProducerId())) {
							producer = producerVertexCurrentAttempt;
						} else {
							LOG.warn("partition {} has already been disposed, skip restarting the producer.",
								predecessorResultPartition);
						}
						break;
					}
				}
			}

			if (producer != null) {
				FailoverRegion producerRegion = vertexToRegion.get(producer.getVertex());
				if (producerRegion == null) {
					executionGraph.failGlobal(new Exception(
						"Can not find a failover region for the execution "
							+ producer.getVertex().getTaskNameWithSubtaskIndex(), cause));
					return;
				}

				if (producerRegion != failoverRegion) {
					LOG.info("Try restarting producer of {} due to DataConsumptionException", taskExecution);

					this.onTaskFailure(producer, new FlinkException(predecessorResultPartition.toString()
						+ " was report error by consumer."));
				}
			}
		}

		// Cancel and restart the region of the target vertex
		LOG.info("Recovering task failure for {} #{} ({}) via restart of failover region",
				ev.getTaskNameWithSubtaskIndex(),
				taskExecution.getAttemptNumber(),
				taskExecution.getAttemptId());

		failoverRegion.onExecutionFail(taskExecution, cause);
	}

	@Override
	public void notifyNewVertices(List<ExecutionJobVertex> newJobVerticesTopological) {
		generateAllFailoverRegion(newJobVerticesTopological);
	}

	@Override
	public String getStrategyName() {
		return "Pipelined Region Failover";
	}

	/**
	 * Generate all the FailoverRegion from the new added job vertexes
 	 */
	private void generateAllFailoverRegion(List<ExecutionJobVertex> newJobVerticesTopological) {
		final IdentityHashMap<ExecutionVertex, ArrayList<ExecutionVertex>> vertexToRegion = new IdentityHashMap<>();

		// we use the map (list -> null) to imitate an IdentityHashSet (which does not exist)
		final IdentityHashMap<ArrayList<ExecutionVertex>, Object> distinctRegions = new IdentityHashMap<>();

		// this loop will worst case iterate over every edge in the graph (complexity is O(#edges))
		
		for (ExecutionJobVertex ejv : newJobVerticesTopological) {

			// currently, jobs with a co-location constraint fail as one
			// we want to improve that in the future (or get rid of co-location constraints)
			if (ejv.getCoLocationGroup() != null) {
				makeAllOneRegion(newJobVerticesTopological);
				return;
			}

			// see if this JobVertex one has pipelined inputs at all
			final List<IntermediateResult> inputs = ejv.getInputs();
			final int numInputs = inputs.size();
			boolean hasPipelinedInputs = false;

			for (IntermediateResult input : inputs) {
				if (input.getResultType().isPipelined()) {
					hasPipelinedInputs = true;
					break;
				}
			}

			if (hasPipelinedInputs) {
				// build upon the predecessors
				for (ExecutionVertex ev : ejv.getTaskVertices()) {

					// remember the region in which we are
					ArrayList<ExecutionVertex> thisRegion = null;

					for (int inputNum = 0; inputNum < numInputs; inputNum++) {
						if (inputs.get(inputNum).getResultType().isPipelined()) {

							for (ExecutionEdge edge : ev.getInputEdges(inputNum)) {
								final ExecutionVertex predecessor = edge.getSource().getProducer();
								final ArrayList<ExecutionVertex> predecessorRegion = vertexToRegion.get(predecessor);

								if (thisRegion != null) {
									// we already have a region. see if it is the same as the predecessor's region
									if (predecessorRegion != thisRegion) {

										// we need to merge our region and the predecessor's region
										predecessorRegion.addAll(thisRegion);
										distinctRegions.remove(thisRegion);
										thisRegion = predecessorRegion;

										// remap the vertices from that merged region
										for (ExecutionVertex inPredRegion: predecessorRegion) {
											vertexToRegion.put(inPredRegion, thisRegion);
										}
									}
								}
								else if (predecessor != null) {
									// first case, make this our region
									thisRegion = predecessorRegion;
									thisRegion.add(ev);
									vertexToRegion.put(ev, thisRegion);
								}
								else {
									// throw an uncaught exception here
									// this is a bug and not a recoverable situation
									throw new FlinkRuntimeException(
											"bug in the logic to construct the pipelined failover regions");
								}
							}
						}
					}
				}
			}
			else {
				// no pipelined inputs, start a new region
				for (ExecutionVertex ev : ejv.getTaskVertices()) {
					ArrayList<ExecutionVertex> region = new ArrayList<>(1);
					region.add(ev);
					vertexToRegion.put(ev, region);
					distinctRegions.put(region, null);
				}
			}
		}

		// now that we have all regions, create the failover region objects 
		LOG.info("Creating {} individual failover regions for job {} ({})",
			distinctRegions.keySet().size(), executionGraph.getJobName(), executionGraph.getJobID());

		for (List<ExecutionVertex> region : distinctRegions.keySet()) {
			final FailoverRegion failoverRegion = new FailoverRegion(executionGraph, executor, region, regionFailLimit);
			for (ExecutionVertex ev : region) {
				this.vertexToRegion.put(ev, failoverRegion);
			}
		}
	}

	private void makeAllOneRegion(List<ExecutionJobVertex> jobVertices) {
		LOG.warn("Cannot decompose ExecutionGraph into individual failover regions due to use of " +
				"Co-Location constraints (iterations). Job will fail over as one holistic unit.");

		final ArrayList<ExecutionVertex> allVertices = new ArrayList<>();

		for (ExecutionJobVertex ejv : jobVertices) {

			// safe some incremental size growing
			allVertices.ensureCapacity(allVertices.size() + ejv.getParallelism());

			for (ExecutionVertex ev : ejv.getTaskVertices()) {
				allVertices.add(ev);
			}
		}

		final FailoverRegion singleRegion = new FailoverRegion(executionGraph, executor, allVertices, regionFailLimit);
		for (ExecutionVertex ev : allVertices) {
			vertexToRegion.put(ev, singleRegion);
		}
	}

	// ------------------------------------------------------------------------
	//  testing
	// ------------------------------------------------------------------------

	/**
	 * Finds the failover region that contains the given execution vertex.
 	 */
	@VisibleForTesting
	public FailoverRegion getFailoverRegion(ExecutionVertex ev) {
		return vertexToRegion.get(ev);
	}

	// ------------------------------------------------------------------------
	//  factory
	// ------------------------------------------------------------------------

	/**
	 * Factory that instantiates the RestartPipelinedRegionStrategy.
	 */
	public static class Factory implements FailoverStrategy.Factory {

		private int regionFailLimit = 100;

		@Override
		public FailoverStrategy create(ExecutionGraph executionGraph) {
			return new RestartPipelinedRegionStrategy(executionGraph, regionFailLimit);
		}

		public void setRegionFailLimit(int regionFailLimit) {
			this.regionFailLimit = regionFailLimit;
		}
	}
}
