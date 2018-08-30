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
package org.flowable.cmmn.engine.impl.el;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.flowable.common.engine.api.delegate.FlowableExpressionEnhancer;

/**
 * @author Joram Barrez
 */
public abstract class AbstractFlowableFunctionExpressionEnhancer implements FlowableExpressionEnhancer {

    protected Pattern pattern;
    
    protected String replacePattern;
    
    /**
     * @param functionPrefixOptions The list of function prefixes, e.g. variables, vars, var
     * @param functionNameOptions The list of function names, e.g. equals, eq
     * @param finalFunctionPrefix The prefix to which all the others will be enhanced to
     * @param finalFunctionName The function name to  which all the others will be enhanced to
     * @param multiParameterFunction Indicates if the function has multiple (more than one) parameter
     */
    public AbstractFlowableFunctionExpressionEnhancer(List<String> functionPrefixOptions, List<String> functionNameOptions, 
                                                      String finalFunctionPrefix, String finalFunctionName,
                                                      boolean multiParameterFunction) {
        
        // Regex for expressions like ${variables:equals(myVar, 123)}  
        //
        // - starts with function name (e.g. variables or vars or var), followed by :
        // - followed by the function name (e.g. equals or eq)
        // - followed by 0 or more whitespaces
        // - followed by a parenthese
        // - followed by 0 or more whitespaces
        // - Optionally followed by a single our double quote
        // - word group
        // - Optionally followed by a single our double quote
        // - followed by 0 or more whitespaces
        // - followed by a comma or a closing parenthese
        this.pattern = Pattern.compile(buildOrWordGroup(functionPrefixOptions) + ":" 
                + buildOrWordGroup(functionNameOptions) 
                + "\\s*\\(\\s*'?\"?(.*?)'?\"?\\s*"
                + (multiParameterFunction ? "," : "\\)"));
        
        this.replacePattern = finalFunctionPrefix + ":" 
                + finalFunctionName 
                + "(planItemInstance,'$3'" // 3th word group: prefix and function name are two first groups
                + (multiParameterFunction ? "," : ")");
        
    }
    
    protected String buildOrWordGroup(List<String> options) {
        StringBuilder strb = new StringBuilder();
        strb.append("(");
        strb.append(options.stream().collect(Collectors.joining("|")));
        strb.append(")");
        return strb.toString();
    }
    
    @Override
    public String enhance(String expressionText) {
        Matcher matcher = pattern.matcher(expressionText);
        if (matcher.find()) {
            return matcher.replaceAll(replacePattern);
        }
        return expressionText;
    }
    
}
