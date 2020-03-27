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

package org.apache.flink.runtime.update.action;

import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;

/**
 * Action to update a JobVertex.
 */
public abstract class JobVertexUpdateAction implements JobGraphUpdateAction {

	/**
	 * The job vertex ID.
	 */
	private final JobVertexID jobVertexID;

	public JobVertexUpdateAction(JobVertexID jobVertexID) {
		this.jobVertexID = jobVertexID;
	}

	public abstract void updateVertex(JobVertex jobVertex);

	@Override
	public void updateJobGraph(JobGraph jobGraph) {
		for (JobVertex vertex : jobGraph.getVertices()) {
			if (jobVertexID.equals(vertex.getID())) {
				updateVertex(vertex);
			}
		}
	}
}
