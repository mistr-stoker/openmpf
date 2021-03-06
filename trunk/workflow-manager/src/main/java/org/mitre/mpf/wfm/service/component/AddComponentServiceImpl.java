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

package org.mitre.mpf.wfm.service.component;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.mitre.mpf.nms.xml.EnvironmentVariable;
import org.mitre.mpf.nms.xml.Service;
import org.mitre.mpf.rest.api.component.ComponentState;
import org.mitre.mpf.rest.api.component.RegisterComponentModel;
import org.mitre.mpf.wfm.pipeline.xml.*;
import org.mitre.mpf.wfm.service.NodeManagerService;
import org.mitre.mpf.wfm.service.PipelineService;
import org.mitre.mpf.wfm.util.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static java.util.stream.Collectors.*;

@org.springframework.stereotype.Service
public class AddComponentServiceImpl implements AddComponentService {

    private static final Logger _log = LoggerFactory.getLogger(AddComponentServiceImpl.class);

    private final PipelineService pipelineService;

    private final NodeManagerService nodeManagerService;

    private final ComponentDeploymentService deployService;

    private final ComponentStateService componentStateService;

    private final ComponentDescriptorValidator componentDescriptorValidator;

    private final ExtrasDescriptorValidator extrasDescriptorValidator;

    private final CustomPipelineValidator customPipelineValidator;

    private final RemoveComponentService removeComponentService;

    private final ObjectMapper objectMapper;

    @Inject
    AddComponentServiceImpl(
            PipelineService pipelineService,
            NodeManagerService nodeManagerService,
            ComponentDeploymentService deployService,
            ComponentStateService componentStateService,
            ComponentDescriptorValidator componentDescriptorValidator,
            ExtrasDescriptorValidator extrasDescriptorValidator,
            CustomPipelineValidator customPipelineValidator,
            RemoveComponentService removeComponentService,
            ObjectMapper objectMapper)
    {
        this.pipelineService = pipelineService;
        this.nodeManagerService = nodeManagerService;
        this.deployService = deployService;
        this.componentStateService = componentStateService;
        this.componentDescriptorValidator = componentDescriptorValidator;
        this.extrasDescriptorValidator = extrasDescriptorValidator;
        this.customPipelineValidator = customPipelineValidator;
        this.removeComponentService = removeComponentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void registerComponent(String componentPackageFileName) throws ComponentRegistrationException {
        ComponentState currentState = componentStateService.getByPackageFile(componentPackageFileName)
                .map(RegisterComponentModel::getComponentState)
                .filter(Objects::nonNull)
                .orElse(ComponentState.UNKNOWN);

        if (currentState != ComponentState.UPLOADED && currentState != ComponentState.REGISTER_ERROR) {
            componentStateService.replacePackageState(componentPackageFileName, ComponentState.REGISTER_ERROR);
            throw new ComponentRegistrationStatusException(currentState);
        }

        try {
            componentStateService.replacePackageState(componentPackageFileName, ComponentState.REGISTERING);

            String descriptorPath = deployService.deployComponent(componentPackageFileName);

            RegisterComponentModel currentModel = componentStateService.getByPackageFile(componentPackageFileName)
                    .orElseThrow(() -> new ComponentRegistrationStatusException(ComponentState.UNKNOWN));
            currentModel.setJsonDescriptorPath(descriptorPath);
            componentStateService.update(currentModel);

            registerDeployedComponent(loadDescriptor(descriptorPath), currentModel);

            currentModel.setDateRegistered(new Date());
            currentModel.setComponentState(ComponentState.REGISTERED);
            componentStateService.update(currentModel);

            String logMsg = "Successfully registered the component from package '" + componentPackageFileName + "'.";
            _log.info(logMsg);
        }
        catch (IllegalStateException | ComponentRegistrationException ex) {
            componentStateService.replacePackageState(componentPackageFileName, ComponentState.REGISTER_ERROR);
            throw ex;
        }
    }

    @Override
    public synchronized void registerDeployedComponent(String descriptorPath) throws ComponentRegistrationException {
        JsonComponentDescriptor descriptor = loadDescriptor(descriptorPath);

        RegisterComponentModel registrationModel = componentStateService
                .getByComponentName(descriptor.componentName)
                .orElseGet(RegisterComponentModel::new);
        registrationModel.setComponentName(descriptor.componentName);
        registrationModel.setJsonDescriptorPath(descriptorPath);

        registerDeployedComponent(descriptor, registrationModel);
        registrationModel.setComponentState(ComponentState.REGISTERED);
        registrationModel.setDateRegistered(new Date());
        componentStateService.update(registrationModel);
    }

    private void registerDeployedComponent(JsonComponentDescriptor descriptor, RegisterComponentModel model)
            throws ComponentRegistrationException {

        if (descriptor.algorithm == null) {
            _log.warn("Component descriptor file is missing an Algorithm definition.");
            _log.warn("Treating as an extras descriptor file. Will register Actions, Tasks, and Pipelines only.");
            extrasDescriptorValidator.validate(new JsonExtrasDescriptor(descriptor));
        } else {
            componentDescriptorValidator.validate(descriptor);
        }

        customPipelineValidator.validate(descriptor);

        AlgorithmDefinition algorithmDef = null;
        String algoName = null;
        if (descriptor.algorithm != null) {
            algorithmDef = convertJsonAlgo(descriptor);
            algoName = saveAlgorithm(algorithmDef);
            model.setAlgorithmName(algoName);
            model.setComponentName(descriptor.componentName);
        }

        try {
            Set<String> savedActions = saveActions(descriptor, algorithmDef);
            model.getActions().addAll(savedActions);

            Set<String> savedTasks = saveTasks(descriptor, algorithmDef);
            model.getTasks().addAll(savedTasks);

            Set<String> savedPipelines = savePipelines(descriptor, algorithmDef);
            model.getPipelines().addAll(savedPipelines);

            if (descriptor.algorithm != null) {
                String serviceName = saveService(descriptor, algorithmDef);
                model.setServiceName(serviceName);
            }
        }
        catch (ComponentRegistrationSubsystemException ex) {
            if (descriptor.algorithm != null) {
                _log.warn("Component registration failed for {}. Removing the {} algorithm and child objects.",
                        descriptor.componentName, algoName);
            } else {
                _log.warn("Component registration failed for {}. Removing child objects.",
                        descriptor.componentName);
            }
            removeComponentService.recursivelyDeleteCustomPipelines(model);
            throw ex;
        }
    }

    private JsonComponentDescriptor loadDescriptor(String descriptorPath) throws FailedToParseDescriptorException {
        try {
            return objectMapper.readValue(new File(descriptorPath), JsonComponentDescriptor.class);
        }
        catch (UnrecognizedPropertyException ex) {
            if (ex.getPropertyName().equals("value")
                    && ex.getReferringClass().equals(JsonComponentDescriptor.AlgoProvidesProp.class)) {
                throw new FailedToParseDescriptorException(
                        "algorithm.providesCollection.properties.value has been renamed to defaultValue. " +
                                "The JSON descriptor must be updated in order to register the component.",
                        ex);
            }
            throw new FailedToParseDescriptorException(ex);
        }
        catch (JsonMappingException ex) {
            throw new FailedToParseDescriptorException(ex);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to load json descriptor ", ex);
        }
    }

    private static AlgorithmDefinition convertJsonAlgo(JsonComponentDescriptor descriptor) {
        JsonComponentDescriptor.Algorithm jsonAlgo = descriptor.algorithm;

        AlgorithmDefinition algoDef = new AlgorithmDefinition(
                jsonAlgo.actionType,
                descriptor.algorithm.name.toUpperCase(),
                jsonAlgo.description);

        jsonAlgo
                .requiresCollection
                .states
                .stream()
                .map(StateDefinitionRef::new)
                .forEach(sd -> algoDef.getRequiresCollection().getStateRefs().add(sd));

        jsonAlgo
                .providesCollection
                .properties
                .stream()
                .map(p -> new PropertyDefinition(p.name, p.type, p.description, p.defaultValue, p.propertiesKey))
                .forEach(pd -> algoDef.getProvidesCollection().getAlgorithmProperties().add(pd));

        jsonAlgo
                .providesCollection
                .states
                .stream()
                .map(StateDefinition::new)
                .forEach(sd -> algoDef.getProvidesCollection().getStates().add(sd));

        return algoDef;
    }

    private static Map<String, String> convertJsonAlgoProps(JsonComponentDescriptor descriptor) {
        return descriptor
                .algorithm
                .providesCollection
                .properties
                .stream()
                .collect(toMap(p -> p.name, p -> p.defaultValue));
    }

    private static List<EnvironmentVariable> convertJsonEnvVars(JsonComponentDescriptor descriptor) {
        return descriptor
                .environmentVariables
                .stream()
                .map(AddComponentServiceImpl::convertJsonEnvVar)
                .collect(toList());
    }

    private static EnvironmentVariable convertJsonEnvVar(JsonComponentDescriptor.EnvironmentVariable jsonEnvVar) {
        EnvironmentVariable newEnvVar = new EnvironmentVariable();
        newEnvVar.setKey(jsonEnvVar.name);
        newEnvVar.setValue(jsonEnvVar.value);
        newEnvVar.setSep(jsonEnvVar.sep);
        return newEnvVar;
    }

    private String saveAlgorithm(AlgorithmDefinition algoDef)
            throws ComponentRegistrationSubsystemException
    {
        Tuple<Boolean, String> result = pipelineService.addAndSaveAlgorithm(algoDef);
        if (result.getFirst()) {
            _log.info("Successfully added the " + algoDef.getName() + " algorithm");
            return algoDef.getName();
        }
        else {
            _log.error(result.getSecond());
            throw new ComponentRegistrationSubsystemException(
                    String.format("Could not add the \"%s\" algorithm.", algoDef.getName()));
        }
    }

    private Set<String> saveActions(JsonComponentDescriptor descriptor, AlgorithmDefinition algorithmDef)
            throws ComponentRegistrationSubsystemException {

        if (descriptor.actions == null) {
            if (algorithmDef == null) {
                return Collections.emptySet();
            }

            // add a default action associated with the algorithm
            String actionDescription = "Default action for the " + algorithmDef.getName() + " algorithm.";
            Map<String, String> algoProps = convertJsonAlgoProps(descriptor);
            String actionName = getDefaultActionName(algorithmDef);
            saveAction(actionName, actionDescription, algorithmDef.getName(), algoProps);
            return Collections.singleton(actionName);
        }

        for (JsonComponentDescriptor.Action action : descriptor.actions) {
            Map<String, String> actionProps = action
                    .properties
                    .stream()
                    .collect(toMap(p -> p.name, p -> p.value));
            saveAction(action.name, action.description, action.algorithm, actionProps);
        }
        return descriptor.actions
                .stream()
                .map(a -> a.name)
                .collect(toSet());
    }

    private void saveAction(String actionName, String description, String algoName, Map<String, String> algoProps)
            throws ComponentRegistrationSubsystemException {
        Tuple<Boolean, String> result = pipelineService.addAndSaveAction(actionName, description, algoName, algoProps);
        if (result.getFirst()) {
            _log.info("Successfully added the {} action for the {} algorithm", actionName, algoName);
        }
        else {
            _log.error(result.getSecond());
            throw new ComponentRegistrationSubsystemException(String.format(
                    "Could not add the %s action", actionName));
        }
    }

    private Set<String> saveTasks(JsonComponentDescriptor descriptor, AlgorithmDefinition algorithmDef)
            throws ComponentRegistrationSubsystemException {

        if (descriptor.tasks == null) {
            if (algorithmDef == null) {
                return Collections.emptySet();
            }

            String actionName = getDefaultActionName(algorithmDef);
            // add a default task associated with the action
            String taskName = getDefaultTaskName(algorithmDef);
            String taskDescription = "Default task for the " + actionName + " action.";
            saveTask(taskName, taskDescription, Collections.singletonList(actionName));
            return Collections.singleton(taskName);
        }

        for (JsonComponentDescriptor.Task task : descriptor.tasks) {
            saveTask(task.name, task.description, task.actions);
        }
        return descriptor.tasks
                .stream()
                .map(t -> t.name)
                .collect(toSet());
    }

    private void saveTask(String taskName, String taskDescription, Collection<String> actionNames)
            throws ComponentRegistrationSubsystemException {
        TaskDefinition task = new TaskDefinition(taskName, taskDescription);
        actionNames.stream()
                .map(ActionDefinitionRef::new)
                .forEach(adr -> task.getActions().add(adr));
        Tuple<Boolean, String> result = pipelineService.addAndSaveTask(task);
        if (result.getFirst()) {
            _log.info("Successfully added the {} task", taskName);
        }
        else {
            _log.error(result.getSecond());
            throw new ComponentRegistrationSubsystemException(String.format("Could not add the %s task", taskName));
        }
    }

    private Set<String> savePipelines(JsonComponentDescriptor descriptor, AlgorithmDefinition algorithmDef)
            throws ComponentRegistrationSubsystemException {

        // add a single action default pipeline associated with the task
        // note, can't do this if a required state must be reached by a previous
        // stage in a pipeline that uses this algorithm
        if (descriptor.pipelines == null) {
            if (algorithmDef != null && algorithmDef.getRequiresCollection().getStateRefs().isEmpty()) {
                String pipelineName = getDefaultPipelineName(algorithmDef);
                String taskName = getDefaultTaskName(algorithmDef);
                String pipelineDescription = "Default pipeline for the " + taskName + " task.";
                savePipeline(pipelineName, pipelineDescription, Collections.singleton(taskName));
                return Collections.singleton(pipelineName);
            }

            return Collections.emptySet();
        }


        for (JsonComponentDescriptor.Pipeline pipeline: descriptor.pipelines) {
            savePipeline(pipeline.name, pipeline.description, pipeline.tasks);
        }
        return descriptor.pipelines
                .stream()
                .map(p -> p.name)
                .collect(toSet());
    }

    private void savePipeline(String pipelineName, String pipelineDescription, Collection<String> taskNames)
            throws ComponentRegistrationSubsystemException {
        PipelineDefinition pipeline = new PipelineDefinition(pipelineName, pipelineDescription);
        taskNames.stream()
                .map(TaskDefinitionRef::new)
                .forEach(tdr -> pipeline.getTaskRefs().add(tdr));

        Tuple<Boolean, String> result = pipelineService.addAndSavePipeline(pipeline);
        if (result.getFirst()) {
            _log.info("Successfully added the {} pipeline.", pipeline.getName());
        }
        else {
            _log.error(result.getSecond());
            throw new ComponentRegistrationSubsystemException(String.format(
                    "Failed to add the %s pipeline", pipeline.getName()));
        }
    }

    private String saveService(JsonComponentDescriptor descriptor, AlgorithmDefinition algorithmDef)
            throws ComponentRegistrationSubsystemException {
        String serviceName = descriptor.componentName;
        if (nodeManagerService.getServiceModels().containsKey(serviceName)) {
            throw new ComponentRegistrationSubsystemException(String.format(
                    "Couldn't add the %s service because another service already has that name", serviceName));
        }
        List<String> componentLaunchArguments = new ArrayList<>(descriptor.launchArgs);
        Service algorithmService;

        if (descriptor.sourceLanguage == ComponentLanguage.JAVA) {
            algorithmService = new Service(serviceName, "${MPF_HOME}/bin/start-service-java.sh");
            algorithmService.addArg("-jar " + descriptor.pathName);
            algorithmService.setWorkingDirectory("${MPF_HOME}/jars");
        }
        else {
            algorithmService = new Service(serviceName, "${MPF_HOME}/bin/" + descriptor.pathName);
            String queueName = String.format("MPF.%s_%s_REQUEST", algorithmDef.getActionType(), algorithmDef.getName());
            componentLaunchArguments.add(1, queueName);
            algorithmService.setLauncher("simple");
            String workingDirectory = "${MPF_HOME}/plugins/" + descriptor.componentName;
            algorithmService.setWorkingDirectory(workingDirectory);
        }

        componentLaunchArguments.forEach(algorithmService::addArg);

        algorithmService.setDescription(algorithmDef.getDescription());
        algorithmService.setEnvVars(convertJsonEnvVars(descriptor));
        _log.debug("Created service definition");
        if (nodeManagerService.addService(algorithmService)) {
            _log.info("Successfully added the {} service", serviceName);
            return serviceName;
        }
        else {
            throw new ComponentRegistrationSubsystemException(
                    String.format("Could not add service %s for deployment to nodes", serviceName));
        }
    }

    private static String getDefaultActionName(AlgorithmDefinition algorithmDef) {
        return String.format("%s %s ACTION", algorithmDef.getName(), algorithmDef.getActionType().toString());
    }

    private static String getDefaultTaskName(AlgorithmDefinition algorithmDef) {
        return String.format("%s %s TASK", algorithmDef.getName(), algorithmDef.getActionType().toString());
    }

    private static String getDefaultPipelineName(AlgorithmDefinition algorithmDef) {
        return String.format("%s %s PIPELINE", algorithmDef.getName(), algorithmDef.getActionType().toString());
    }
}
