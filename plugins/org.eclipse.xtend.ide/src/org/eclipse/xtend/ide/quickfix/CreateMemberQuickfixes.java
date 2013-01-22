/*******************************************************************************
 * Copyright (c) 2013 itemis AG (http://www.itemis.eu) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.xtend.ide.quickfix;

import static com.google.common.collect.Lists.*;
import static org.eclipse.xtext.util.Strings.*;

import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtend.core.xtend.XtendClass;
import org.eclipse.xtend.core.xtend.XtendMember;
import org.eclipse.xtend.ide.codebuilder.AbstractConstructorBuilder;
import org.eclipse.xtend.ide.codebuilder.AbstractFieldBuilder;
import org.eclipse.xtend.ide.codebuilder.AbstractMethodBuilder;
import org.eclipse.xtend.ide.codebuilder.CodeBuilderFactory;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.common.types.JvmDeclaredType;
import org.eclipse.xtext.common.types.JvmIdentifiableElement;
import org.eclipse.xtext.common.types.JvmPrimitiveType;
import org.eclipse.xtext.common.types.JvmTypeReference;
import org.eclipse.xtext.common.types.JvmVisibility;
import org.eclipse.xtext.common.types.util.Primitives;
import org.eclipse.xtext.common.types.util.Primitives.Primitive;
import org.eclipse.xtext.common.types.util.TypeReferences;
import org.eclipse.xtext.common.types.util.jdt.IJavaElementFinder;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.edit.IModificationContext;
import org.eclipse.xtext.ui.editor.model.edit.ISemanticModification;
import org.eclipse.xtext.ui.editor.model.edit.SemanticModificationWrapper;
import org.eclipse.xtext.ui.editor.quickfix.IssueResolutionAcceptor;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.xtext.xbase.XAbstractFeatureCall;
import org.eclipse.xtext.xbase.XAssignment;
import org.eclipse.xtext.xbase.XBinaryOperation;
import org.eclipse.xtext.xbase.XConstructorCall;
import org.eclipse.xtext.xbase.XExpression;
import org.eclipse.xtext.xbase.XFeatureCall;
import org.eclipse.xtext.xbase.XMemberFeatureCall;
import org.eclipse.xtext.xbase.XUnaryOperation;
import org.eclipse.xtext.xbase.XbasePackage;
import org.eclipse.xtext.xbase.compiler.StringBuilderBasedAppendable;
import org.eclipse.xtext.xbase.jvmmodel.ILogicalContainerProvider;
import org.eclipse.xtext.xbase.scoping.featurecalls.OperatorMapping;
import org.eclipse.xtext.xbase.typing.ITypeProvider;
import org.eclipse.xtext.xbase.ui.contentassist.ReplacingAppendable;
import org.eclipse.xtext.xbase.ui.quickfix.ILinkingIssueQuickfixProvider;

import com.google.inject.Inject;

/**
 * @author Jan Koehnlein - Initial contribution and API
 */
public class CreateMemberQuickfixes implements ILinkingIssueQuickfixProvider {

	private static final Logger LOG = Logger.getLogger(CreateMemberQuickfixes.class);
	
	@Inject
	private ITypeProvider typeProvider;

	@Inject
	private ReplacingAppendable.Factory appendableFactory;

	@Inject
	private TypeReferences typeRefs;

	@Inject
	private Primitives primitives;

	@Inject
	private ILogicalContainerProvider logicalContainerProvider;
	
	@Inject
	private IJavaElementFinder javaElementFinder;
	
	@Inject
	private TypeResolver typeResolver;
	
	@Inject 
	private OperatorMapping operatorMapping;
	
	@Inject
	private CodeBuilderFactory codeBuilderFactory;
	
	@Inject
	private CodeBuilderQuickfix quickfixFactory;
	
	public void addQuickfixes(Issue issue, IssueResolutionAcceptor issueResolutionAcceptor,
			IXtextDocument xtextDocument, XtextResource resource, EObject referenceOwner, EReference unresolvedReference)
			throws Exception {
		if (referenceOwner instanceof XAbstractFeatureCall) {
			XAbstractFeatureCall call = (XAbstractFeatureCall) referenceOwner;
			
			String newMemberName = (issue.getData() != null && issue.getData().length > 0) ? issue.getData()[0] : null;
			if(newMemberName != null) {
				if (call instanceof XMemberFeatureCall) {
					if(!call.isExplicitOperationCallOrBuilderSyntax()) { 
						newFieldQuickfix(newMemberName, call, issue, issueResolutionAcceptor);
						newGetterQuickfixes(newMemberName, call, issue, issueResolutionAcceptor);
					}
					newMethodQuickfixes(newMemberName, call, issue, issueResolutionAcceptor);
					
				} else if(call instanceof XFeatureCall) {
					if(!call.isExplicitOperationCallOrBuilderSyntax()) {
						newLocalVariableQuickfix(newMemberName, call, issue, issueResolutionAcceptor);
						newFieldQuickfix(newMemberName, call, issue, issueResolutionAcceptor);
						newGetterQuickfixes(newMemberName, call, issue, issueResolutionAcceptor);
					}
					newMethodQuickfixes(newMemberName, call, issue, issueResolutionAcceptor);
					
				} else if (call instanceof XAssignment) {
					newSetterQuickfix(issue, issueResolutionAcceptor, newMemberName, call);
					if(((XAssignment) call).getAssignable() == null) {
						newLocalVariableQuickfix(newMemberName, call, issue, issueResolutionAcceptor);
						newFieldQuickfix(newMemberName, call, issue, issueResolutionAcceptor);
					}
				}
			} 
			if (call instanceof XBinaryOperation || call instanceof XUnaryOperation) {
				JvmIdentifiableElement feature = call.getFeature();
				if(feature.eIsProxy()) {
					String operatorMethodName = getOperatorMethodName(call);
					if(operatorMethodName != null) 
						newMethodQuickfixes(operatorMethodName, call, issue, issueResolutionAcceptor);
				}
			}
		}
		if(referenceOwner instanceof XConstructorCall) {
			newConstructorQuickfix(issue, issueResolutionAcceptor, (XConstructorCall) referenceOwner);
		}
	}

	protected String getAccessorMethodName(String prefix, String fieldName) {
		return prefix + fieldName.substring(0,1).toUpperCase() + fieldName.substring(1);
	}

	protected JvmTypeReference getNewMemberType(XAbstractFeatureCall call) {
		if(call instanceof XAssignment) {
			XExpression value = ((XAssignment) call).getValue();
			return typeResolver.resolveType(call, typeProvider.getType(value));
		} else {
			JvmTypeReference expectedType = typeProvider.getExpectedType(call);
			return (expectedType != null) ? typeResolver.resolveType(call, expectedType) : null;
		}
	}
	
	protected JvmTypeReference getReceiverType(XAbstractFeatureCall featureCall) {
		XExpression actualReceiver = featureCall.getActualReceiver();
		if(actualReceiver == null) {
			return typeRefs.createTypeRef(getCallersType(featureCall));
		} else {
			JvmTypeReference typeRef = typeProvider.getType(actualReceiver);
			if(typeRef.getType() instanceof JvmDeclaredType)
				return typeResolver.resolveType(featureCall, typeRef);
		}
		return null;
	}

	protected JvmDeclaredType getCallersType(XExpression call) {
		JvmIdentifiableElement nearestLogicalContainer = logicalContainerProvider.getNearestLogicalContainer(call);
		return EcoreUtil2.getContainerOfType(nearestLogicalContainer, JvmDeclaredType.class);
	}
	
	protected String getOperatorMethodName(XAbstractFeatureCall call) {
		for(INode node: NodeModelUtils.findNodesForFeature(call, XbasePackage.Literals.XABSTRACT_FEATURE_CALL__FEATURE)) {
			for(ILeafNode leafNode: node.getLeafNodes()) {
				if(!leafNode.isHidden()) {
					String symbol = leafNode.getText();
					QualifiedName methodName = operatorMapping.getMethodName(QualifiedName.create(symbol));
					if(methodName != null)
						return methodName.getFirstSegment();
				}
			}
		}
		return null;
	}
	
	protected void newLocalVariableQuickfix(final String variableName, XAbstractFeatureCall call, Issue issue,
			IssueResolutionAcceptor issueResolutionAcceptor) {
		JvmTypeReference variableType = getNewMemberType(call);
		final StringBuilderBasedAppendable localVarDescriptionBuilder = new StringBuilderBasedAppendable();
		localVarDescriptionBuilder.append("...").newLine();
		final String defaultValueLiteral = getDefaultValueLiteral(variableType);
		localVarDescriptionBuilder.append("val ").append(variableName).append(" = ").append(defaultValueLiteral);
		localVarDescriptionBuilder.newLine().append("...");
		issueResolutionAcceptor.accept(issue, "Create local variable '" + variableName + "'",
				localVarDescriptionBuilder.toString(), "fix_local_var.png",
				new SemanticModificationWrapper(issue.getUriToProblem(), new ISemanticModification() {
					public void apply(final EObject element, final IModificationContext context) throws Exception {
						if (element != null) {
							XtendMember xtendMember = EcoreUtil2.getContainerOfType(element, XtendMember.class);
							if (xtendMember != null) {
								int offset = getFirstOffsetOfKeyword(xtendMember, "{");
								IXtextDocument xtextDocument = context.getXtextDocument();
								if (offset != -1 && xtextDocument != null) {
									final ReplacingAppendable appendable = appendableFactory.get(xtextDocument,
											element, offset, 0, 1, false);
									appendable.increaseIndentation().newLine().append("val ").append(variableName).append(" = ")
											.append(defaultValueLiteral);
									appendable.commitChanges();
								}
							}
						}
					}
				}));
	}
	
	protected void newMethodQuickfixes(String newMemberName, XAbstractFeatureCall call, 
			final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		JvmDeclaredType callersType = getCallersType(call);
		JvmTypeReference receiverType = getReceiverType(call);
		JvmTypeReference newMemberType = getNewMemberType(call);
		List<JvmTypeReference> argumentTypes = getResolvedArgumentTypes(call, call.getActualArguments());
		newMethodQuickfixes(receiverType, newMemberName, newMemberType, argumentTypes, call, callersType, issue,
				issueResolutionAcceptor);
	}

	protected void newMethodQuickfixes(JvmTypeReference containerType, String name, JvmTypeReference returnType,
			List<JvmTypeReference> argumentTypes, XAbstractFeatureCall call, JvmDeclaredType callersType,
			final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		boolean isLocal = callersType == containerType.getType();
		if(containerType.getType() instanceof JvmDeclaredType) 
			newMethodQuickfix((JvmDeclaredType) containerType.getType(), name, returnType, argumentTypes, false, isLocal, call, issue, issueResolutionAcceptor);
		if(!isLocal) {
			List<JvmTypeReference> extensionMethodParameterTypes = newArrayList(argumentTypes);
			extensionMethodParameterTypes.add(0, containerType);
			newMethodQuickfix(callersType, name, returnType, extensionMethodParameterTypes, true, true, call, issue, issueResolutionAcceptor);
		}
	}
	
	protected void newMethodQuickfix(JvmDeclaredType containerType, String name, JvmTypeReference returnType,
			List<JvmTypeReference> parameterTypes, boolean isExtension, boolean isLocal, XAbstractFeatureCall call, 
			final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		if(!javaElementFinder.findElementFor(containerType).isReadOnly()) {
			AbstractMethodBuilder methodBuilder = codeBuilderFactory.createMethodBuilder(containerType);
			methodBuilder.setMethodName(name);
			methodBuilder.setReturnType(returnType);
			methodBuilder.setParameterTypes(parameterTypes);
			methodBuilder.setContext(call);
			methodBuilder.setVisibility(JvmVisibility.PUBLIC);
			StringBuffer label = new StringBuffer("Create ");
			if(isExtension)
				label.append("extension ");
			label.append("method '").append(name).append("(");
			boolean isFirst = true;
			for(JvmTypeReference parameterType: parameterTypes) {
				if(!isFirst) 
					label.append(", ");
				isFirst = false;
				label.append(parameterType.getSimpleName());
			}
			label.append(")'");
			if(!isLocal)
				label.append(" in '" + containerType.getSimpleName() + "'");
			quickfixFactory.addQuickfix(methodBuilder, label.toString(), issue, issueResolutionAcceptor);
		}
	}
	
	protected void newSetterQuickfix(Issue issue, IssueResolutionAcceptor issueResolutionAcceptor,
			String newMemberName, XAbstractFeatureCall call) {
		newMethodQuickfixes(getAccessorMethodName("set", newMemberName), call, issue, issueResolutionAcceptor);
	}

	protected void newGetterQuickfixes(String name, XAbstractFeatureCall call, 
			final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		JvmDeclaredType callersType = getCallersType(call);
		JvmTypeReference receiverType = getReceiverType(call);
		JvmTypeReference fieldType = getNewMemberType(call);
		newMethodQuickfixes(receiverType, getAccessorMethodName("get", name), 
				fieldType, Collections.<JvmTypeReference>emptyList(), call, 
				callersType, issue, issueResolutionAcceptor);
	}

	protected void newFieldQuickfix(String name, XAbstractFeatureCall call, 
			final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		JvmDeclaredType callersType = getCallersType(call);
		JvmTypeReference receiverType = getReceiverType(call);
		JvmTypeReference fieldType = getNewMemberType(call);
		if(callersType == receiverType.getType()) 
			newFieldQuickfix(callersType, name, fieldType, call, issue, issueResolutionAcceptor);
	}

	protected void newFieldQuickfix(JvmDeclaredType containerType, String name, JvmTypeReference fieldType,
			XAbstractFeatureCall call, final Issue issue, final IssueResolutionAcceptor issueResolutionAcceptor) {
		if(!javaElementFinder.findElementFor(containerType).isReadOnly()) {
			AbstractFieldBuilder fieldBuilder = codeBuilderFactory.createFieldBuilder(containerType);
			fieldBuilder.setFieldName(name);
			fieldBuilder.setFieldType(fieldType);
			fieldBuilder.setContext(call);
			fieldBuilder.setVisibility(JvmVisibility.PRIVATE);
			StringBuilder label = new StringBuilder("Create field '").append(name).append("'");
			quickfixFactory.addQuickfix(fieldBuilder, label.toString(), issue, issueResolutionAcceptor);
		}
	}
	
	protected void newConstructorQuickfix(Issue issue, IssueResolutionAcceptor issueResolutionAcceptor,
			XConstructorCall call) {
		JvmDeclaredType ownerType = call.getConstructor().getDeclaringType();
		if(ownerType != null) {
			AbstractConstructorBuilder constructorBuilder = codeBuilderFactory.createConstructorBuilder(ownerType);
			constructorBuilder.setContext(call);
			constructorBuilder.setParameterTypes(getResolvedArgumentTypes(call, call.getArguments()));
			constructorBuilder.setVisibility(JvmVisibility.PUBLIC);
			StringBuffer label = new StringBuffer("Create constructor '");
			if(constructorBuilder.getOwnerSource() instanceof XtendClass)
				label.append("new");
			else
				label.append(ownerType.getSimpleName());
			label.append("(");
			boolean isFirst = true;
			for(JvmTypeReference parameterType: constructorBuilder.getParameterTypes()) {
				if(!isFirst) 
					label.append(", ");
				isFirst = false;
				label.append(parameterType.getSimpleName());
			}
			label.append(")'");
			if(getCallersType(call) != ownerType) 
				label.append(" in '").append(ownerType.getSimpleName()).append("'");
			quickfixFactory.addQuickfix(constructorBuilder, label.toString(), issue, issueResolutionAcceptor);
		}
	}
	
	/**
	 * @since 2.3
	 */
	protected int getFirstOffsetOfKeyword(EObject object, String keyword) {
		int offset = -1;
		if (object != null) {
			ICompositeNode node = NodeModelUtils.getNode(object);
			if (node != null) {
				for (ILeafNode leafNode : node.getLeafNodes()) {
					if (leafNode.getGrammarElement() instanceof Keyword
							&& equal(keyword, ((Keyword) leafNode.getGrammarElement()).getValue())) {
						return leafNode.getOffset() + 1;
					}
				}
			}
		}
		return offset;
	}

	/**
	 * @since 2.3
	 */
	protected String getDefaultValueLiteral(JvmTypeReference type) {
		if (primitives.isPrimitive(type)) {
			Primitive primitiveKind = primitives.primitiveKind((JvmPrimitiveType) type.getType());
			if (primitiveKind == Primitive.Boolean)
				return "false";
			else
				return "0 as " + type.getSimpleName();
		}
		return "null";
	}

	protected List<JvmTypeReference> getResolvedArgumentTypes(EObject context, List<XExpression> arguments) {
		List<JvmTypeReference> argumentTypes = newArrayList();
		for(XExpression argument: arguments) {
			JvmTypeReference argumentType = typeProvider.getType(argument);
			JvmTypeReference resolved = typeResolver.resolveType(context, argumentType);
			if(resolved == null) 
				LOG.error("Could not resolve argument type", new Exception());
			else
				argumentTypes.add(resolved);
		}
		return argumentTypes;

	}
}
