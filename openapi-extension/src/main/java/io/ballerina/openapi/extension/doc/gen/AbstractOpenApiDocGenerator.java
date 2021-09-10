/*
 * Copyright (c) 2021, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.ballerina.openapi.extension.doc.gen;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.ListenerDeclarationNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeLocation;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.openapi.converter.OpenApiConverterException;
import io.ballerina.openapi.converter.utils.ServiceToOpenAPIConverterUtils;
import io.ballerina.openapi.extension.Constants;
import io.ballerina.openapi.extension.OpenApiDiagnosticCode;
import io.ballerina.openapi.extension.context.OpenApiDocContext;
import io.ballerina.projects.Package;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.Diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.ballerina.openapi.extension.context.OpenApiDocContextHandler.getContextHandler;
import static io.ballerina.openapi.extension.doc.DocGenerationUtils.getDiagnostics;

/**
 * {@code AbstractOpenApiDocGenerator} contains the basic utilities required for OpenAPI doc generation.
 */
public abstract class AbstractOpenApiDocGenerator implements OpenApiDocGenerator {
    private static final String OPEN_API_DOC_NAME_FORMAT = "%d.json";

    private final OpenApiContractResolver contractResolver;

    public AbstractOpenApiDocGenerator() {
        this.contractResolver = new OpenApiContractResolver();
    }

    @Override
    public void generate(OpenApiDocConfig config, SyntaxNodeAnalysisContext context, NodeLocation location) {
        try {
            String openApiDocName = String.format(OPEN_API_DOC_NAME_FORMAT, config.getServiceSymbol().hashCode());

            Package currentPackage = config.getCurrentPackage();
            Path srcRoot = currentPackage.project().sourceRoot();

            // find the project root path
            Path projectRoot = retrieveProjectRoot(srcRoot);

            ServiceDeclarationNode serviceNode = config.getServiceNode();
            Optional<AnnotationNode> serviceInfoAnnotationOpt = getServiceInfoAnnotation(serviceNode);
            if (serviceInfoAnnotationOpt.isPresent()) {
                // use the available open-api doc and add it to resource directory
                AnnotationNode serviceInfoAnnotation = serviceInfoAnnotationOpt.get();
                Optional<Path> openApiContractOpt = this.contractResolver.resolve(serviceInfoAnnotation, projectRoot);
                if (openApiContractOpt.isEmpty()) {
                    // could not find the open-api contract file, hence will not proceed
                    OpenApiDiagnosticCode errorCode = OpenApiDiagnosticCode.OPENAPI_101;
                    updateCompilerContext(context, location, errorCode);
                    return;
                }
                String openApiDefinition = Files.readString(openApiContractOpt.get());
                updateOpenApiContext(currentPackage, srcRoot, openApiDocName, openApiDefinition);
            } else {
                // generate open-api doc and add it to resource directory
                String openApiDefinition = generateOpenApiDoc(
                        config.getSemanticModel(), config.getSyntaxTree(), serviceNode, openApiDocName);
                if (null != openApiDefinition && !openApiDefinition.isBlank()) {
                    updateOpenApiContext(currentPackage, srcRoot, openApiDocName, openApiDefinition);
                } else {
                    OpenApiDiagnosticCode errorCode = OpenApiDiagnosticCode.OPENAPI_107;
                    updateCompilerContext(context, location, errorCode);
                }
            }
        } catch (IOException | OpenApiConverterException e) {
            OpenApiDiagnosticCode errorCode = OpenApiDiagnosticCode.OPENAPI_102;
            updateCompilerContext(context, location, errorCode);
        } catch (Exception e) {
            OpenApiDiagnosticCode errorCode = OpenApiDiagnosticCode.OPENAPI_103;
            updateCompilerContext(context, location, errorCode);
        }
    }

    private void updateOpenApiContext(Package currentPackage, Path srcRoot, String openApiDocName,
                                      String openApiDefinition) {
        OpenApiDocContext.OpenApiDefinition openApiDef = new OpenApiDocContext
                .OpenApiDefinition(openApiDocName, openApiDefinition);
        getContextHandler().updateContext(currentPackage.packageId(), srcRoot, openApiDef);
    }

    private void updateCompilerContext(SyntaxNodeAnalysisContext context, NodeLocation location,
                                       OpenApiDiagnosticCode errorCode) {
        Diagnostic diagnostic = getDiagnostics(errorCode, location);
        context.reportDiagnostic(diagnostic);
    }

    private Optional<AnnotationNode> getServiceInfoAnnotation(ServiceDeclarationNode serviceNode) {
        Optional<MetadataNode> metadata = serviceNode.metadata();
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        MetadataNode metaData = metadata.get();
        NodeList<AnnotationNode> annotations = metaData.annotations();
        return annotations.stream()
                .filter(ann -> Constants.SERVICE_INFO_ANNOTATION.equals(ann.toString().trim()))
                .findFirst();
    }

    private String generateOpenApiDoc(SemanticModel semanticModel, SyntaxTree syntaxTree,
                                      ServiceDeclarationNode serviceNode, String outputFileName)
            throws OpenApiConverterException {
        ModulePartNode modulePartNode = syntaxTree.rootNode();
        List<ListenerDeclarationNode> listenerNodes = extractListenerNodes(modulePartNode);
        String serviceBasePath = getServiceBasePath(serviceNode);
        return ServiceToOpenAPIConverterUtils.generateOASForGivenFormat(
                serviceNode, serviceBasePath, true, listenerNodes, semanticModel, outputFileName);
    }

    private List<ListenerDeclarationNode> extractListenerNodes(ModulePartNode modulePartNode) {
        return modulePartNode.members().stream()
                .filter(n -> SyntaxKind.LISTENER_DECLARATION.equals(n.kind()))
                .map(n -> (ListenerDeclarationNode) n)
                .collect(Collectors.toList());
    }

    private String getServiceBasePath(ServiceDeclarationNode serviceDefinition) {
        StringBuilder currentServiceName = new StringBuilder();
        NodeList<Node> serviceNameNodes = serviceDefinition.absoluteResourcePath();
        for (Node serviceBasedPathNode : serviceNameNodes) {
            currentServiceName.append(serviceBasedPathNode.toString());
        }
        return currentServiceName.toString().trim();
    }

    protected Path retrieveProjectRoot(Path projectRoot) {
        return projectRoot;
    }

    // current design for resources directory structure is as follows :
    //  <executable.jar>
    //      - [resources]
    //          - [ballerina]
    //              - [http]
    protected Path retrieveResourcePath(Path projectRoot) {
        return projectRoot
                .resolve(Constants.TARGET_DIR_NAME)
                .resolve(Paths.get(Constants.BIN_DIR_NAME, Constants.RESOURCES_DIR_NAME))
                .resolve(Constants.PACKAGE_ORG).resolve(Constants.PACKAGE_NAME);
    }
}
