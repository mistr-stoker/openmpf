/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2016 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2016 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.wfm.service;

import org.mitre.mpf.wfm.pipeline.xml.AlgorithmDefinition;
import org.mitre.mpf.wfm.pipeline.xml.PipelineDefinition;
import org.mitre.mpf.wfm.pipeline.xml.PropertyDefinition;
import org.mitre.mpf.wfm.pipeline.xml.TaskDefinition;
import org.mitre.mpf.wfm.util.Tuple;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface PipelineService {

	// =========================
	// Pipeline service methods.
	// =========================

	/** Gets the ordered listing of pipelines supported by the system. */
	public List<String> getPipelineNames();

	/** Gets the ordered listing of tasks supported by the system. */
	public List<String> getTaskNames();

	/** Gets the ordered listing of actions supported by the system. */
	public List<String> getActionNames();

	/** Gets the ordered listing of algorithms supported by the system */
	public List<String> getAlgorithmNames();

	/** Gets the properties associated with the given algorithm. If no such algorithm exists, an empty collection is returned. */
	public List<PropertyDefinition> getAlgorithmProperties(String algorithmName);

	/** Gets the pipelines definition XML as an JSON string */
	public String getPipelineDefinitionAsJson();
	
	/** Creates a new algorithm based on the provided algorithm definition */
	public boolean addAlgorithm(AlgorithmDefinition algorithm);

	/** Creates a new algorithm based on the provided algorithm definition and saves it to XML */
	public Tuple<Boolean, String> addAndSaveAlgorithm(AlgorithmDefinition algorithm);

	/** Creates a new action with the given name and description based on the provided algorithm and using the specified properties. */
	public Tuple<Boolean,String> addAndSaveAction(String actionName, String actionDescription, String algorithmName, Map<String, String> propertySettings);

	/** Creates a new task based on the provided definition. */
	public boolean addTask(TaskDefinition task);

	/** Creates a new task based on the provided task definition and saves it to XML */
	public Tuple<Boolean, String> addAndSaveTask(TaskDefinition task);

	/** Creates a new pipeline from the provided definition. */
	public boolean addPipeline(PipelineDefinition pipeline);

	/** Creates a new pipeline based on the provided pipeline definition and saves it to XML */
	public Tuple<Boolean, String> addAndSavePipeline(PipelineDefinition pipeline);

	/** Removes the algorithm with the provided name from both memory and XML */
	public void removeAndDeleteAlgorithm(String algorithmName);

	/** Removes the action with the provided name from both memory and XML */
	public void removeAndDeleteAction(String actionName);

	/** Removes the task with the provided name from both memory and XML */
	public void removeAndDeleteTask(String taskName);

	/** Removes the pipeline with the provided name from both memory and XML */
	public void removeAndDeletePipeline(String pipelineName);

	public Tuple<Boolean,String> savePipelineChanges(String type);
}
