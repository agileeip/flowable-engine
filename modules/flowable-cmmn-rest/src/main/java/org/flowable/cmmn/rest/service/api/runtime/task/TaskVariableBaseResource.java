/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.cmmn.rest.service.api.runtime.task;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.flowable.cmmn.api.CmmnRuntimeService;
import org.flowable.cmmn.rest.service.api.CmmnRestResponseFactory;
import org.flowable.cmmn.rest.service.api.engine.variable.RestVariable;
import org.flowable.cmmn.rest.service.api.engine.variable.RestVariable.RestVariableScope;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.common.rest.exception.FlowableContentNotSupportedException;
import org.flowable.task.api.Task;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * @author Frederik Heremans
 */
public class TaskVariableBaseResource extends TaskBaseResource implements InitializingBean {

    @Autowired
    protected Environment env;

    @Autowired
    protected CmmnRuntimeService runtimeService;

    protected boolean isSerializableVariableAllowed;

    @Override
    public void afterPropertiesSet() {
        isSerializableVariableAllowed = env.getProperty("rest.variables.allow.serializable", Boolean.class, true);
    }

    public RestVariable getVariableFromRequest(String taskId, String variableName, String scope, boolean includeBinary) {
        Task task = getTaskFromRequestWithoutAccessCheck(taskId);
        
        boolean variableFound = false;
        Object value = null;
        RestVariableScope variableScope = RestVariable.getScopeFromString(scope);
        if (restApiInterceptor != null) {
            restApiInterceptor.accessTaskVariable(task, variableName);
        }

        if (variableScope == null) {
            // First, check local variables (which have precedence when no scope is supplied)
            if (taskService.hasVariableLocal(taskId, variableName)) {
                value = taskService.getVariableLocal(taskId, variableName);
                variableScope = RestVariableScope.LOCAL;
                variableFound = true;
            } else {
                // Revert to execution-variable when not present local on the task
                if (ScopeTypes.CMMN.equals(task.getScopeType()) && task.getScopeId() != null && runtimeService.hasVariable(task.getScopeId(), variableName)) {
                    value = runtimeService.getVariable(task.getScopeId(), variableName);
                    variableScope = RestVariableScope.GLOBAL;
                    variableFound = true;
                }
            }

        } else if (variableScope == RestVariableScope.GLOBAL) {
            if (ScopeTypes.CMMN.equals(task.getScopeType()) && task.getScopeId() != null && runtimeService.hasVariable(task.getScopeId(), variableName)) {
                value = runtimeService.getVariable(task.getScopeId(), variableName);
                variableFound = true;
            }

        } else if (variableScope == RestVariableScope.LOCAL) {
            if (taskService.hasVariableLocal(taskId, variableName)) {
                value = taskService.getVariableLocal(taskId, variableName);
                variableFound = true;
            }
        }

        if (!variableFound) {
            throw new FlowableObjectNotFoundException("Task '" + taskId + "' doesn't have a variable with name: '" + variableName + "'.", VariableInstanceEntity.class);
        } else {
            return restResponseFactory.createRestVariable(variableName, value, variableScope, taskId, CmmnRestResponseFactory.VARIABLE_TASK, includeBinary);
        }
    }

    protected boolean hasVariableOnScope(Task task, String variableName, RestVariableScope scope) {
        boolean variableFound = false;

        if (scope == RestVariableScope.GLOBAL) {
            if (ScopeTypes.CMMN.equals(task.getScopeType()) && task.getScopeId() != null && runtimeService.hasVariable(task.getScopeId(), variableName)) {
                variableFound = true;
            }

        } else if (scope == RestVariableScope.LOCAL) {
            if (taskService.hasVariableLocal(task.getId(), variableName)) {
                variableFound = true;
            }
        }
        return variableFound;
    }

    protected RestVariable setBinaryVariable(MultipartHttpServletRequest request, Task task, boolean isNew) {

        // Validate input and set defaults
        if (request.getFileMap().size() == 0) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        // Get first file in the map, ignore possible other files
        MultipartFile file = request.getFile(request.getFileMap().keySet().iterator().next());

        if (file == null) {
            throw new FlowableIllegalArgumentException("No file content was found in request body.");
        }

        String variableScope = null;
        String variableName = null;
        String variableType = null;

        Map<String, String[]> paramMap = request.getParameterMap();
        for (String parameterName : paramMap.keySet()) {

            if (paramMap.get(parameterName).length > 0) {

                if ("scope".equalsIgnoreCase(parameterName)) {
                    variableScope = paramMap.get(parameterName)[0];

                } else if ("name".equalsIgnoreCase(parameterName)) {
                    variableName = paramMap.get(parameterName)[0];

                } else if ("type".equalsIgnoreCase(parameterName)) {
                    variableType = paramMap.get(parameterName)[0];
                }
            }
        }

        try {

            if (variableName == null) {
                throw new FlowableIllegalArgumentException("No variable name was found in request body.");
            }

            if (variableType != null) {
                if (!CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE.equals(variableType) && !CmmnRestResponseFactory.SERIALIZABLE_VARIABLE_TYPE.equals(variableType)) {
                    throw new FlowableIllegalArgumentException("Only 'binary' and 'serializable' are supported as variable type.");
                }
            } else {
                variableType = CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE;
            }

            RestVariableScope scope = RestVariableScope.LOCAL;
            if (variableScope != null) {
                scope = RestVariable.getScopeFromString(variableScope);
            }

            if (variableType.equals(CmmnRestResponseFactory.BYTE_ARRAY_VARIABLE_TYPE)) {
                // Use raw bytes as variable value
                byte[] variableBytes = IOUtils.toByteArray(file.getInputStream());
                setVariable(task, variableName, variableBytes, scope, isNew);

            } else if (isSerializableVariableAllowed) {
                // Try deserializing the object
                ObjectInputStream stream = new ObjectInputStream(file.getInputStream());
                Object value = stream.readObject();
                setVariable(task, variableName, value, scope, isNew);
                stream.close();

            } else {
                throw new FlowableContentNotSupportedException("Serialized objects are not allowed");
            }

            return restResponseFactory.createBinaryRestVariable(variableName, scope, variableType, task.getId(), CmmnRestResponseFactory.VARIABLE_TASK);

        } catch (IOException ioe) {
            throw new FlowableIllegalArgumentException("Error getting binary variable", ioe);
        } catch (ClassNotFoundException ioe) {
            throw new FlowableContentNotSupportedException("The provided body contains a serialized object for which the class was not found: " + ioe.getMessage());
        }

    }

    protected RestVariable setSimpleVariable(RestVariable restVariable, Task task, boolean isNew) {
        if (restVariable.getName() == null) {
            throw new FlowableIllegalArgumentException("Variable name is required");
        }

        // Figure out scope, revert to local is omitted
        RestVariableScope scope = restVariable.getVariableScope();
        if (scope == null) {
            scope = RestVariableScope.LOCAL;
        }

        Object actualVariableValue = restResponseFactory.getVariableValue(restVariable);
        setVariable(task, restVariable.getName(), actualVariableValue, scope, isNew);

        return restResponseFactory.createRestVariable(restVariable.getName(), actualVariableValue, scope, task.getId(), CmmnRestResponseFactory.VARIABLE_TASK, false);
    }

    protected void setVariable(Task task, String name, Object value, RestVariableScope scope, boolean isNew) {
        // Create can only be done on new variables. Existing variables should
        // be updated using PUT
        boolean hasVariable = hasVariableOnScope(task, name, scope);
        if (isNew && hasVariable) {
            throw new FlowableException("Variable '" + name + "' is already present on task '" + task.getId() + "'.");
        }

        if (!isNew && !hasVariable) {
            throw new FlowableObjectNotFoundException("Task '" + task.getId() + "' doesn't have a variable with name: '" + name + "'.", null);
        }

        if (restApiInterceptor != null) {
            if (isNew) {
                restApiInterceptor.createTaskVariables(task, Collections.singletonMap(name, value), scope);
            } else {
                restApiInterceptor.updateTaskVariables(task, Collections.singletonMap(name, value), scope);
            }
        }

        if (scope == RestVariableScope.LOCAL) {
            taskService.setVariableLocal(task.getId(), name, value);
        } else {
            if (ScopeTypes.CMMN.equals(task.getScopeType()) && task.getScopeId() != null) {
                // Explicitly set on execution, setting non-local variable on
                // task will override local-variable if exists
                runtimeService.setVariable(task.getScopeId(), name, value);
            } else {
                // Standalone task, no global variables possible
                throw new FlowableIllegalArgumentException("Cannot set global variable '" + name + "' on task '" + task.getId() + "', task is not part of process.");
            }
        }
    }
}
