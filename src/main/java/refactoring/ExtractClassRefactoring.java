package gr.uom.java.jdeodorant.refactoring.manipulators;

import gr.uom.java.ast.ASTReader;
import gr.uom.java.ast.decomposition.cfg.PlainVariable;
import gr.uom.java.ast.util.ExpressionExtractor;
import gr.uom.java.ast.util.MethodDeclarationUtility;
import gr.uom.java.ast.util.StatementExtractor;
import gr.uom.java.ast.util.ThrownExceptionVisitor;
import gr.uom.java.ast.util.TypeVisitor;
import gr.uom.java.ast.util.math.AdjacencyList;
import gr.uom.java.ast.util.math.Edge;
import gr.uom.java.ast.util.math.Node;
import gr.uom.java.ast.util.math.TarjanAlgorithm;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression.Operator;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.CreateCompilationUnitChange;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.ChangeDescriptor;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringChangeDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;

@SuppressWarnings("restriction")
public class ExtractClassRefactoring extends Refactoring {
	private static final String GETTER_PREFIX = "get";
	private static final String SETTER_PREFIX = "set";
	private static final String ACCESSOR_SUFFIX = "2";
	private IFile sourceFile;
	private CompilationUnit sourceCompilationUnit;
	private TypeDeclaration sourceTypeDeclaration;
	private Map<ICompilationUnit, CompilationUnitChange> compilationUnitChanges;
	private Map<ICompilationUnit, CreateCompilationUnitChange> createCompilationUnitChanges;
	private Set<IJavaElement> javaElementsToOpenInEditor;
	private Set<ITypeBinding> requiredImportDeclarationsInExtractedClass;
	private Map<MethodDeclaration, Set<PlainVariable>> additionalArgumentsAddedToExtractedMethods;
	private Map<MethodDeclaration, Set<SingleVariableDeclaration>> additionalParametersAddedToExtractedMethods;
	private Set<String> sourceMethodBindingsChangedWithPublicModifier;
	private Set<String> sourceFieldBindingsWithCreatedSetterMethod;
	private Set<String> sourceFieldBindingsWithCreatedGetterMethod;
	private Set<FieldDeclaration> fieldDeclarationsChangedWithPublicModifier;
	private Set<BodyDeclaration> memberTypeDeclarationsChangedWithPublicModifier;
	private Map<MethodDeclaration, Set<MethodInvocation>> oldMethodInvocationsWithinExtractedMethods;
	private Map<MethodDeclaration, Set<MethodInvocation>> newMethodInvocationsWithinExtractedMethods;
	private Map<MethodDeclaration, MethodDeclaration> oldToNewExtractedMethodDeclarationMap;
	private Set<VariableDeclaration> extractedFieldFragments;
	private Set<MethodDeclaration> extractedMethods;
	private Set<MethodDeclaration> delegateMethods;
	private String extractedTypeName;
	private boolean leaveDelegateForPublicMethods;
	private Map<Statement, ASTRewrite> statementRewriteMap;
	//this map holds for each constructor the assignment statements that initialize final extracted fields
	private Map<MethodDeclaration, Map<VariableDeclaration, Assignment>> constructorFinalFieldAssignmentMap;
	//this map hold the parameters that should be passed in each constructor of the extracted class
	private Map<MethodDeclaration, Set<VariableDeclaration>> extractedClassConstructorParameterMap;
	private Set<VariableDeclaration> extractedFieldsWithThisExpressionInTheirInitializer;
	private Set<IMethodBinding> staticallyImportedMethods;

	public ExtractClassRefactoring(IFile sourceFile, CompilationUnit sourceCompilationUnit, TypeDeclaration sourceTypeDeclaration,
								   Set<VariableDeclaration> extractedFieldFragments, Set<MethodDeclaration> extractedMethods, Set<MethodDeclaration> delegateMethods, String extractedTypeName) {
		this.sourceFile = sourceFile;
		this.sourceCompilationUnit = sourceCompilationUnit;
		this.sourceTypeDeclaration = sourceTypeDeclaration;
		this.compilationUnitChanges = new LinkedHashMap<ICompilationUnit, CompilationUnitChange>();
		ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
		MultiTextEdit sourceMultiTextEdit = new MultiTextEdit();
		CompilationUnitChange sourceCompilationUnitChange = new CompilationUnitChange("", sourceICompilationUnit);
		sourceCompilationUnitChange.setEdit(sourceMultiTextEdit);
		this.compilationUnitChanges.put(sourceICompilationUnit, sourceCompilationUnitChange);
		this.createCompilationUnitChanges = new LinkedHashMap<ICompilationUnit, CreateCompilationUnitChange>();
		this.javaElementsToOpenInEditor = new LinkedHashSet<IJavaElement>();
		this.requiredImportDeclarationsInExtractedClass = new LinkedHashSet<ITypeBinding>();
		this.additionalArgumentsAddedToExtractedMethods = new LinkedHashMap<MethodDeclaration, Set<PlainVariable>>();
		this.additionalParametersAddedToExtractedMethods = new LinkedHashMap<MethodDeclaration, Set<SingleVariableDeclaration>>();
		this.sourceMethodBindingsChangedWithPublicModifier = new LinkedHashSet<String>();
		this.sourceFieldBindingsWithCreatedSetterMethod = new LinkedHashSet<String>();
		this.sourceFieldBindingsWithCreatedGetterMethod = new LinkedHashSet<String>();
		this.fieldDeclarationsChangedWithPublicModifier = new LinkedHashSet<FieldDeclaration>();
		this.memberTypeDeclarationsChangedWithPublicModifier = new LinkedHashSet<BodyDeclaration>();
		this.oldMethodInvocationsWithinExtractedMethods = new LinkedHashMap<MethodDeclaration, Set<MethodInvocation>>();
		this.newMethodInvocationsWithinExtractedMethods = new LinkedHashMap<MethodDeclaration, Set<MethodInvocation>>();
		this.oldToNewExtractedMethodDeclarationMap = new LinkedHashMap<MethodDeclaration, MethodDeclaration>();
		this.extractedFieldFragments = extractedFieldFragments;
		this.extractedMethods = extractedMethods;
		this.delegateMethods = delegateMethods;
		this.extractedTypeName = extractedTypeName;
		this.leaveDelegateForPublicMethods = false;
		this.statementRewriteMap = new LinkedHashMap<Statement, ASTRewrite>();
		this.constructorFinalFieldAssignmentMap = new LinkedHashMap<MethodDeclaration, Map<VariableDeclaration, Assignment>>();
		this.extractedClassConstructorParameterMap = new LinkedHashMap<MethodDeclaration, Set<VariableDeclaration>>();
		this.extractedFieldsWithThisExpressionInTheirInitializer = new LinkedHashSet<VariableDeclaration>();
		this.staticallyImportedMethods = new LinkedHashSet<IMethodBinding>();
		for(MethodDeclaration extractedMethod : extractedMethods) {
			additionalArgumentsAddedToExtractedMethods.put(extractedMethod, new LinkedHashSet<PlainVariable>());
			additionalParametersAddedToExtractedMethods.put(extractedMethod, new LinkedHashSet<SingleVariableDeclaration>());
		}
	}

	private void handleAccessedFieldNotHavingSetterMethod(MethodDeclaration sourceMethod,
														  MethodDeclaration newMethodDeclaration, ASTRewrite targetRewriter, AST ast,
														  Map<PlainVariable, SingleVariableDeclaration> fieldParameterMap, SimpleName newAccessedVariable, IVariableBinding accessedVariableBinding) {
		Set<PlainVariable> additionalArgumentsAddedToMovedMethod = additionalArgumentsAddedToExtractedMethods.get(sourceMethod);
		Set<SingleVariableDeclaration> additionalParametersAddedToMovedMethod = additionalParametersAddedToExtractedMethods.get(sourceMethod);
		if(!containsVariable(accessedVariableBinding, additionalArgumentsAddedToMovedMethod)) {
			SingleVariableDeclaration fieldParameter = addParameterToMovedMethod(newMethodDeclaration, accessedVariableBinding, targetRewriter);
			addVariable(accessedVariableBinding, additionalArgumentsAddedToMovedMethod);
			additionalParametersAddedToMovedMethod.add(fieldParameter);
			fieldParameterMap.put(new PlainVariable(accessedVariableBinding), fieldParameter);
		}
		if(newAccessedVariable.getParent() instanceof FieldAccess) {
			FieldAccess fieldAccess = (FieldAccess)newAccessedVariable.getParent();
			if(fieldAccess.getExpression() instanceof ThisExpression) {
				String parameterName = createNameForParameterizedFieldAccess(fieldAccess.getName().getIdentifier());
				targetRewriter.replace(newAccessedVariable.getParent(), ast.newSimpleName(parameterName), null);
			}
		}
		else if(newAccessedVariable.getParent() instanceof QualifiedName) {

		}
		else {
			String parameterName = createNameForParameterizedFieldAccess(accessedVariableBinding.getName());
			targetRewriter.replace(newAccessedVariable, ast.newSimpleName(parameterName), null);
		}
	}

	private SingleVariableDeclaration handleAccessedFieldHavingSetterMethod(MethodDeclaration sourceMethod,
																			MethodDeclaration newMethodDeclaration, ASTRewrite targetRewriter,
																			AST ast, SingleVariableDeclaration sourceClassParameter,
																			String modifiedSourceTypeName, SimpleName newAccessedVariable, IVariableBinding accessedVariableBinding) {
		IMethodBinding getterMethodBinding = findGetterMethodInSourceClass(accessedVariableBinding);
		Set<PlainVariable> additionalArgumentsAddedToMovedMethod = additionalArgumentsAddedToExtractedMethods.get(sourceMethod);
		Set<SingleVariableDeclaration> additionalParametersAddedToMovedMethod = additionalParametersAddedToExtractedMethods.get(sourceMethod);
		if(!containsThisVariable(additionalArgumentsAddedToMovedMethod)) {
			sourceClassParameter = addSourceClassParameterToMovedMethod(newMethodDeclaration, targetRewriter);
			addThisVariable(additionalArgumentsAddedToMovedMethod);
			additionalParametersAddedToMovedMethod.add(sourceClassParameter);
		}
		MethodInvocation getterMethodInvocation = ast.newMethodInvocation();
		if(getterMethodBinding != null) {
			targetRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(getterMethodBinding.getName()), null);
		}
		else {
			if(!sourceFieldBindingsWithCreatedGetterMethod.contains(accessedVariableBinding.getKey())) {
				createGetterMethodInSourceClass(accessedVariableBinding);
				sourceFieldBindingsWithCreatedGetterMethod.add(accessedVariableBinding.getKey());
			}
			String originalFieldName = accessedVariableBinding.getName();
			String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
			String getterMethodName = GETTER_PREFIX + modifiedFieldName;
			getterMethodName = appendAccessorMethodSuffix(getterMethodName, sourceTypeDeclaration.getMethods());
			targetRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(getterMethodName), null);
		}
		if(newAccessedVariable.getParent() instanceof FieldAccess) {
			FieldAccess newFieldAccess = (FieldAccess)newAccessedVariable.getParent();
			if(newFieldAccess.getExpression() instanceof ThisExpression) {
				targetRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, ast.newSimpleName(modifiedSourceTypeName), null);
				targetRewriter.replace(newFieldAccess, getterMethodInvocation, null);
			}
		}
		else if(newAccessedVariable.getParent() instanceof QualifiedName) {
			targetRewriter.replace(newAccessedVariable, getterMethodInvocation, null);
		}
		else {
			targetRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, ast.newSimpleName(modifiedSourceTypeName), null);
			targetRewriter.replace(newAccessedVariable, getterMethodInvocation, null);
		}
		return sourceClassParameter;
	}

	private void addVariable(IVariableBinding variableBinding, Set<PlainVariable> additionalArgumentsAddedToMovedMethod) {
		PlainVariable variable = new PlainVariable(variableBinding);
		additionalArgumentsAddedToMovedMethod.add(variable);
	}

	private void addThisVariable(Set<PlainVariable> additionalArgumentsAddedToMovedMethod) {
		PlainVariable variable = new PlainVariable("this", "this", "this", false, false, false);
		additionalArgumentsAddedToMovedMethod.add(variable);
	}

	private boolean isThisVariable(PlainVariable argument) {
		return argument.getVariableBindingKey().equals("this");
	}

	private boolean containsThisVariable(Set<PlainVariable> additionalArgumentsAddedToMovedMethod) {
		for(PlainVariable argument : additionalArgumentsAddedToMovedMethod) {
			if(isThisVariable(argument)) {
				return true;
			}
		}
		return false;
	}

	private boolean containsVariable(IVariableBinding variableBinding, Set<PlainVariable> additionalArgumentsAddedToMovedMethod) {
		for(PlainVariable argument : additionalArgumentsAddedToMovedMethod) {
			if(argument.getVariableBindingKey().equals(variableBinding.getKey())) {
				return true;
			}
		}
		return false;
	}

	private boolean declaredInSourceTypeDeclarationOrSuperclass(IVariableBinding variableBinding) {
		return RefactoringUtility.findDeclaringTypeDeclaration(variableBinding, sourceTypeDeclaration) != null;
	}

	private SingleVariableDeclaration addSourceClassParameterToMovedMethod(MethodDeclaration newMethodDeclaration, ASTRewrite targetRewriter) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		SimpleName typeName = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
		Type parameterType = ast.newSimpleType(typeName);
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, parameterType, null);
		String sourceTypeName = sourceTypeDeclaration.getName().getIdentifier();
		String modifiedSourceTypeName = sourceTypeName.substring(0,1).toLowerCase() + sourceTypeName.substring(1,sourceTypeName.length());
		SimpleName parameterName = ast.newSimpleName(modifiedSourceTypeName);
		targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, parameterName, null);
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
		typeBindings.add(sourceTypeDeclaration.resolveBinding());
		RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
		return parameter;
	}

	private SingleVariableDeclaration addParameterToMovedMethod(MethodDeclaration newMethodDeclaration, PlainVariable additionalArgument, ASTRewrite targetRewriter) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		VariableDeclaration field = RefactoringUtility.findFieldDeclaration(additionalArgument, sourceTypeDeclaration);
		FieldDeclaration fieldDeclaration = (FieldDeclaration)field.getParent();
		Type fieldType = fieldDeclaration.getType();
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, fieldType, null);
		if(additionalArgument.isField()) {
			//adding "this" prefix to avoid collisions with other parameter names
			String parameterName = createNameForParameterizedFieldAccess(field.getName().getIdentifier());
			targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, ast.newSimpleName(parameterName), null);
		}
		else {
			targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, field.getName(), null);
		}
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
		typeBindings.add(fieldType.resolveBinding());
		RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
		return parameter;
	}

	private SingleVariableDeclaration addParameterToMovedMethod(MethodDeclaration newMethodDeclaration, IVariableBinding variableBinding, ASTRewrite targetRewriter) {
		AST ast = newMethodDeclaration.getAST();
		SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
		ITypeBinding typeBinding = variableBinding.getType();
		Type fieldType = RefactoringUtility.generateTypeFromTypeBinding(typeBinding, ast, targetRewriter);
		targetRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, fieldType, null);
		if(variableBinding.isField()) {
			//adding "this" prefix to avoid collisions with other parameter names
			String parameterName = createNameForParameterizedFieldAccess(variableBinding.getName());
			targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, ast.newSimpleName(parameterName), null);
		}
		else {
			targetRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, ast.newSimpleName(variableBinding.getName()), null);
		}
		ListRewrite parametersRewrite = targetRewriter.getListRewrite(newMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		parametersRewrite.insertLast(parameter, null);
		Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
		typeBindings.add(variableBinding.getType());
		RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
		return parameter;
	}

	private String createNameForParameterizedFieldAccess(String fieldName) {
		return "this" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1, fieldName.length());
	}

	private void setPublicModifierToSourceMethod(IMethodBinding methodBinding, TypeDeclaration sourceTypeDeclaration) {
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		for(MethodDeclaration methodDeclaration : methodDeclarations) {
			if(methodDeclaration.resolveBinding().isEqualTo(methodBinding)) {
				CompilationUnit sourceCompilationUnit = RefactoringUtility.findCompilationUnit(methodDeclaration);
				ASTRewrite sourceRewriter = ASTRewrite.create(sourceCompilationUnit.getAST());
				ListRewrite modifierRewrite = sourceRewriter.getListRewrite(methodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
				Modifier publicModifier = methodDeclaration.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
				boolean modifierFound = false;
				List<IExtendedModifier> modifiers = methodDeclaration.modifiers();
				for(IExtendedModifier extendedModifier : modifiers) {
					if(extendedModifier.isModifier()) {
						Modifier modifier = (Modifier)extendedModifier;
						if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)) {
							modifierFound = true;
						}
						else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD)) {
							modifierFound = true;
							modifierRewrite.replace(modifier, publicModifier, null);
							updateAccessModifier(sourceRewriter, sourceCompilationUnit);
						}
						else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)) {
							modifierFound = true;
							IPackageBinding sourceTypeDeclarationPackageBinding = this.sourceTypeDeclaration.resolveBinding().getPackage();
							IPackageBinding typeDeclarationPackageBinding = sourceTypeDeclaration.resolveBinding().getPackage();
							if(sourceTypeDeclarationPackageBinding != null && typeDeclarationPackageBinding != null &&
									!sourceTypeDeclarationPackageBinding.isEqualTo(typeDeclarationPackageBinding)) {
								modifierRewrite.replace(modifier, publicModifier, null);
								updateAccessModifier(sourceRewriter, sourceCompilationUnit);
							}
						}
					}
				}
				if(!modifierFound) {
					modifierRewrite.insertFirst(publicModifier, null);
					updateAccessModifier(sourceRewriter, sourceCompilationUnit);
				}
			}
		}
	}

	private void updateAccessModifier(ASTRewrite sourceRewriter, CompilationUnit sourceCompilationUnit) {
		try {
			TextEdit sourceEdit = sourceRewriter.rewriteAST();
			ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
			CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
			if(change == null) {
				MultiTextEdit sourceMultiTextEdit = new MultiTextEdit();
				change = new CompilationUnitChange("", sourceICompilationUnit);
				change.setEdit(sourceMultiTextEdit);
				compilationUnitChanges.put(sourceICompilationUnit, change);
			}
			change.getEdit().addChild(sourceEdit);
			change.addTextEditGroup(new TextEditGroup("Update access modifier to public", new TextEdit[] {sourceEdit}));
		}
		catch(JavaModelException javaModelException) {
			javaModelException.printStackTrace();
		}
	}

	private void modifySourceStaticFieldInstructionsInTargetClass(MethodDeclaration sourceMethod,
																  MethodDeclaration newMethodDeclaration, ASTRewrite targetRewriter) {
		ExpressionExtractor extractor = new ExpressionExtractor();
		List<Expression> sourceVariableInstructions = extractor.getVariableInstructions(sourceMethod.getBody());
		List<Expression> newVariableInstructions = extractor.getVariableInstructions(newMethodDeclaration.getBody());
		int i = 0;
		for(Expression expression : sourceVariableInstructions) {
			SimpleName simpleName = (SimpleName)expression;
			IBinding binding = simpleName.resolveBinding();
			if(binding != null && binding.getKind() == IBinding.VARIABLE) {
				IVariableBinding variableBinding = (IVariableBinding)binding;
				if(variableBinding.isField() && (variableBinding.getModifiers() & Modifier.STATIC) != 0) {
					if(declaredInSourceTypeDeclarationOrSuperclass(variableBinding)) {
						AST ast = newMethodDeclaration.getAST();
						SimpleName qualifier = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
						if(simpleName.getParent() instanceof FieldAccess) {
							FieldAccess fieldAccess = (FieldAccess)newVariableInstructions.get(i).getParent();
							targetRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, qualifier, null);
						}
						else if(RefactoringUtility.needsQualifier(simpleName)) {
							SimpleName newSimpleName = ast.newSimpleName(simpleName.getIdentifier());
							QualifiedName newQualifiedName = ast.newQualifiedName(qualifier, newSimpleName);
							targetRewriter.replace(newVariableInstructions.get(i), newQualifiedName, null);
						}
						setPublicModifierToSourceField(variableBinding);
					}
					else {
						AST ast = newMethodDeclaration.getAST();
						SimpleName qualifier = null;
						if((variableBinding.getModifiers() & Modifier.PUBLIC) != 0) {
							qualifier = ast.newSimpleName(variableBinding.getDeclaringClass().getName());
							Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
							typeBindings.add(variableBinding.getDeclaringClass());
							RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
						}
						else {
							qualifier = ast.newSimpleName(sourceTypeDeclaration.getName().getIdentifier());
						}
						if(simpleName.getParent() instanceof FieldAccess) {
							FieldAccess fieldAccess = (FieldAccess)newVariableInstructions.get(i).getParent();
							targetRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, qualifier, null);
						}
						else if(RefactoringUtility.needsQualifier(simpleName)) {
							SimpleName newSimpleName = ast.newSimpleName(simpleName.getIdentifier());
							QualifiedName newQualifiedName = ast.newQualifiedName(qualifier, newSimpleName);
							targetRewriter.replace(newVariableInstructions.get(i), newQualifiedName, null);
						}
						ITypeBinding fieldDeclaringClass = variableBinding.getDeclaringClass();
						if(fieldDeclaringClass != null && fieldDeclaringClass.isEnum() && sourceTypeDeclaration.resolveBinding().isEqualTo(fieldDeclaringClass.getDeclaringClass())) {
							setPublicModifierToSourceMemberType(fieldDeclaringClass);
						}
					}
				}
			}
			i++;
		}
	}

	private void setPublicModifierToSourceMemberType(ITypeBinding typeBinding) {
		List<BodyDeclaration> bodyDeclarations = sourceTypeDeclaration.bodyDeclarations();
		for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
			if(bodyDeclaration instanceof TypeDeclaration) {
				TypeDeclaration memberType = (TypeDeclaration)bodyDeclaration;
				ITypeBinding memberTypeBinding = memberType.resolveBinding();
				if(typeBinding.isEqualTo(memberTypeBinding)) {
					updateBodyDeclarationAccessModifier(memberType, TypeDeclaration.MODIFIERS2_PROPERTY);
				}
			}
			else if(bodyDeclaration instanceof EnumDeclaration) {
				EnumDeclaration memberEnum = (EnumDeclaration)bodyDeclaration;
				ITypeBinding memberTypeBinding = memberEnum.resolveBinding();
				if(typeBinding.isEqualTo(memberTypeBinding)) {
					updateBodyDeclarationAccessModifier(memberEnum, EnumDeclaration.MODIFIERS2_PROPERTY);
				}
			}
		}
	}

	private void updateBodyDeclarationAccessModifier(BodyDeclaration memberType, ChildListPropertyDescriptor childListPropertyDescriptor) {
		ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		ListRewrite modifierRewrite = sourceRewriter.getListRewrite(memberType, childListPropertyDescriptor);
		Modifier publicModifier = memberType.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
		boolean modifierFound = false;
		List<IExtendedModifier> modifiers = memberType.modifiers();
		for(IExtendedModifier extendedModifier : modifiers) {
			if(extendedModifier.isModifier()) {
				Modifier modifier = (Modifier)extendedModifier;
				if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)) {
					modifierFound = true;
				}
				else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD)) {
					if(!memberTypeDeclarationsChangedWithPublicModifier.contains(memberType)) {
						memberTypeDeclarationsChangedWithPublicModifier.add(memberType);
						modifierFound = true;
						modifierRewrite.replace(modifier, publicModifier, null);
						try {
							TextEdit sourceEdit = sourceRewriter.rewriteAST();
							ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
							CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
							change.getEdit().addChild(sourceEdit);
							change.addTextEditGroup(new TextEditGroup("Change access level to public", new TextEdit[] {sourceEdit}));
						} catch (JavaModelException e) {
							e.printStackTrace();
						}
					}
				}
				else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)) {
					modifierFound = true;
				}
			}
		}
		if(!modifierFound) {
			if(!memberTypeDeclarationsChangedWithPublicModifier.contains(memberType)) {
				memberTypeDeclarationsChangedWithPublicModifier.add(memberType);
				modifierRewrite.insertFirst(publicModifier, null);
				try {
					TextEdit sourceEdit = sourceRewriter.rewriteAST();
					ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
					CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
					change.getEdit().addChild(sourceEdit);
					change.addTextEditGroup(new TextEditGroup("Set access level to public", new TextEdit[] {sourceEdit}));
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void setPublicModifierToSourceField(IVariableBinding variableBinding) {
		FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
		for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
			List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
			for(VariableDeclarationFragment fragment : fragments) {
				boolean modifierIsReplaced = false;
				if(variableBinding.isEqualTo(fragment.resolveBinding())) {
					ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
					ListRewrite modifierRewrite = sourceRewriter.getListRewrite(fieldDeclaration, FieldDeclaration.MODIFIERS2_PROPERTY);
					Modifier publicModifier = fieldDeclaration.getAST().newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD);
					boolean modifierFound = false;
					List<IExtendedModifier> modifiers = fieldDeclaration.modifiers();
					for(IExtendedModifier extendedModifier : modifiers) {
						if(extendedModifier.isModifier()) {
							Modifier modifier = (Modifier)extendedModifier;
							if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PUBLIC_KEYWORD)) {
								modifierFound = true;
							}
							else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PRIVATE_KEYWORD)) {
								if(!fieldDeclarationsChangedWithPublicModifier.contains(fieldDeclaration)) {
									fieldDeclarationsChangedWithPublicModifier.add(fieldDeclaration);
									modifierFound = true;
									modifierRewrite.replace(modifier, publicModifier, null);
									modifierIsReplaced = true;
									try {
										TextEdit sourceEdit = sourceRewriter.rewriteAST();
										ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
										CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
										change.getEdit().addChild(sourceEdit);
										change.addTextEditGroup(new TextEditGroup("Change access level to public", new TextEdit[] {sourceEdit}));
									} catch (JavaModelException e) {
										e.printStackTrace();
									}
								}
							}
							else if(modifier.getKeyword().equals(Modifier.ModifierKeyword.PROTECTED_KEYWORD)) {
								modifierFound = true;
							}
						}
					}
					if(!modifierFound) {
						if(!fieldDeclarationsChangedWithPublicModifier.contains(fieldDeclaration)) {
							fieldDeclarationsChangedWithPublicModifier.add(fieldDeclaration);
							modifierRewrite.insertFirst(publicModifier, null);
							modifierIsReplaced = true;
							try {
								TextEdit sourceEdit = sourceRewriter.rewriteAST();
								ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
								CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
								change.getEdit().addChild(sourceEdit);
								change.addTextEditGroup(new TextEditGroup("Set access level to public", new TextEdit[] {sourceEdit}));
							} catch (JavaModelException e) {
								e.printStackTrace();
							}
						}
					}
				}
				if(modifierIsReplaced)
					break;
			}
		}
	}

	private MethodDeclaration createSetterMethodDeclaration(VariableDeclaration fieldFragment, AST extractedClassAST, ASTRewrite extractedClassRewriter) {
		String originalFieldName = fieldFragment.getName().getIdentifier();
		String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
		MethodDeclaration setterMethodDeclaration = extractedClassAST.newMethodDeclaration();
		String setterMethodName = SETTER_PREFIX + modifiedFieldName;
		setterMethodName = appendAccessorMethodSuffix(setterMethodName, this.extractedMethods);
		extractedClassRewriter.set(setterMethodDeclaration, MethodDeclaration.NAME_PROPERTY, extractedClassAST.newSimpleName(setterMethodName), null);
		PrimitiveType type = extractedClassAST.newPrimitiveType(PrimitiveType.VOID);
		extractedClassRewriter.set(setterMethodDeclaration, MethodDeclaration.RETURN_TYPE2_PROPERTY, type, null);
		ListRewrite setterMethodModifiersRewrite = extractedClassRewriter.getListRewrite(setterMethodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
		setterMethodModifiersRewrite.insertLast(extractedClassAST.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD), null);
		SingleVariableDeclaration parameter = extractedClassAST.newSingleVariableDeclaration();
		extractedClassRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, fieldFragment.getName(), null);
		FieldDeclaration originalFieldDeclaration = (FieldDeclaration)fieldFragment.getParent();
		extractedClassRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, originalFieldDeclaration.getType(), null);
		ListRewrite setterMethodParametersRewrite = extractedClassRewriter.getListRewrite(setterMethodDeclaration, MethodDeclaration.PARAMETERS_PROPERTY);
		setterMethodParametersRewrite.insertLast(parameter, null);
		if((originalFieldDeclaration.getModifiers() & Modifier.STATIC) != 0) {
			setterMethodModifiersRewrite.insertLast(extractedClassAST.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD), null);
		}

		Assignment assignment = extractedClassAST.newAssignment();
		FieldAccess fieldAccess = extractedClassAST.newFieldAccess();
		if((originalFieldDeclaration.getModifiers() & Modifier.STATIC) != 0) {
			extractedClassRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, extractedClassAST.newSimpleName(extractedTypeName), null);
		}
		else {
			ThisExpression thisExpression = extractedClassAST.newThisExpression();
			extractedClassRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, thisExpression, null);
		}
		extractedClassRewriter.set(fieldAccess, FieldAccess.NAME_PROPERTY, fieldFragment.getName(), null);
		extractedClassRewriter.set(assignment, Assignment.LEFT_HAND_SIDE_PROPERTY, fieldAccess, null);
		extractedClassRewriter.set(assignment, Assignment.OPERATOR_PROPERTY, Assignment.Operator.ASSIGN, null);
		extractedClassRewriter.set(assignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, fieldFragment.getName(), null);
		ExpressionStatement expressionStatement = extractedClassAST.newExpressionStatement(assignment);
		Block setterMethodBody = extractedClassAST.newBlock();
		ListRewrite setterMethodBodyRewrite = extractedClassRewriter.getListRewrite(setterMethodBody, Block.STATEMENTS_PROPERTY);
		setterMethodBodyRewrite.insertLast(expressionStatement, null);
		extractedClassRewriter.set(setterMethodDeclaration, MethodDeclaration.BODY_PROPERTY, setterMethodBody, null);
		return setterMethodDeclaration;
	}

	private MethodDeclaration createGetterMethodDeclaration(VariableDeclaration fieldFragment, AST extractedClassAST, ASTRewrite extractedClassRewriter) {
		String originalFieldName = fieldFragment.getName().getIdentifier();
		String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
		MethodDeclaration getterMethodDeclaration = extractedClassAST.newMethodDeclaration();
		String getterMethodName = GETTER_PREFIX + modifiedFieldName;
		getterMethodName = appendAccessorMethodSuffix(getterMethodName, this.extractedMethods);
		extractedClassRewriter.set(getterMethodDeclaration, MethodDeclaration.NAME_PROPERTY, extractedClassAST.newSimpleName(getterMethodName), null);
		FieldDeclaration originalFieldDeclaration = (FieldDeclaration)fieldFragment.getParent();
		extractedClassRewriter.set(getterMethodDeclaration, MethodDeclaration.RETURN_TYPE2_PROPERTY, originalFieldDeclaration.getType(), null);
		ListRewrite getterMethodModifiersRewrite = extractedClassRewriter.getListRewrite(getterMethodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
		getterMethodModifiersRewrite.insertLast(extractedClassAST.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD), null);
		if((originalFieldDeclaration.getModifiers() & Modifier.STATIC) != 0) {
			getterMethodModifiersRewrite.insertLast(extractedClassAST.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD), null);
		}
		ReturnStatement returnStatement = extractedClassAST.newReturnStatement();
		extractedClassRewriter.set(returnStatement, ReturnStatement.EXPRESSION_PROPERTY, fieldFragment.getName(), null);
		Block getterMethodBody = extractedClassAST.newBlock();
		ListRewrite getterMethodBodyRewrite = extractedClassRewriter.getListRewrite(getterMethodBody, Block.STATEMENTS_PROPERTY);
		getterMethodBodyRewrite.insertLast(returnStatement, null);
		extractedClassRewriter.set(getterMethodDeclaration, MethodDeclaration.BODY_PROPERTY, getterMethodBody, null);
		return getterMethodDeclaration;
	}

	private void addStaticImportDeclaration(IMethodBinding methodBinding, CompilationUnit targetCompilationUnit, ASTRewrite targetRewriter) {
		AST ast = targetCompilationUnit.getAST();
		ImportDeclaration importDeclaration = ast.newImportDeclaration();
		QualifiedName qualifiedName = ast.newQualifiedName(ast.newName(methodBinding.getDeclaringClass().getQualifiedName()), ast.newSimpleName(methodBinding.getName()));
		targetRewriter.set(importDeclaration, ImportDeclaration.NAME_PROPERTY, qualifiedName, null);
		targetRewriter.set(importDeclaration, ImportDeclaration.STATIC_PROPERTY, true, null);
		ListRewrite importRewrite = targetRewriter.getListRewrite(targetCompilationUnit, CompilationUnit.IMPORTS_PROPERTY);
		importRewrite.insertLast(importDeclaration, null);
	}

	private void addImportDeclaration(ITypeBinding typeBinding, CompilationUnit targetCompilationUnit, ASTRewrite targetRewriter) {
		String qualifiedName = typeBinding.getQualifiedName();
		String qualifiedPackageName = "";
		if(qualifiedName.contains("."))
			qualifiedPackageName = qualifiedName.substring(0,qualifiedName.lastIndexOf("."));
		PackageDeclaration sourcePackageDeclaration = sourceCompilationUnit.getPackage();
		String sourcePackageDeclarationName = "";
		if(sourcePackageDeclaration != null)
			sourcePackageDeclarationName = sourcePackageDeclaration.getName().getFullyQualifiedName();
		if(!qualifiedPackageName.equals("") && !qualifiedPackageName.equals("java.lang") &&
				((!qualifiedPackageName.equals(sourcePackageDeclarationName) && !typeBinding.isNested()) ||
						(typeBinding.isNested() && sourceTypeDeclaration.resolveBinding().isEqualTo(typeBinding)) ||
						(qualifiedPackageName.equals(sourceTypeDeclaration.resolveBinding().getQualifiedName()) && typeBinding.isMember()))) {
			List<ImportDeclaration> importDeclarationList = targetCompilationUnit.imports();
			boolean found = false;
			for(ImportDeclaration importDeclaration : importDeclarationList) {
				if(!importDeclaration.isOnDemand()) {
					if(qualifiedName.equals(importDeclaration.getName().getFullyQualifiedName())) {
						found = true;
						break;
					}
				}
				else {
					if(qualifiedPackageName.equals(importDeclaration.getName().getFullyQualifiedName())) {
						found = true;
						break;
					}
				}
			}
			if(!found) {
				AST ast = targetCompilationUnit.getAST();
				ImportDeclaration importDeclaration = ast.newImportDeclaration();
				targetRewriter.set(importDeclaration, ImportDeclaration.NAME_PROPERTY, ast.newName(qualifiedName), null);
				ListRewrite importRewrite = targetRewriter.getListRewrite(targetCompilationUnit, CompilationUnit.IMPORTS_PROPERTY);
				importRewrite.insertLast(importDeclaration, null);
			}
		}
	}

	private void createExtractedTypeFieldReferenceInSourceClass() {
		ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		AST contextAST = sourceTypeDeclaration.getAST();
		ListRewrite contextBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
		VariableDeclarationFragment extractedReferenceFragment = contextAST.newVariableDeclarationFragment();
		String modifiedExtractedTypeName = extractedTypeName.substring(0,1).toLowerCase() + extractedTypeName.substring(1,extractedTypeName.length());
		sourceRewriter.set(extractedReferenceFragment, VariableDeclarationFragment.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
		if(constructorFinalFieldAssignmentMap.isEmpty()) {
			ClassInstanceCreation initializer = contextAST.newClassInstanceCreation();
			Type targetType = contextAST.newSimpleType(contextAST.newName(extractedTypeName));
			sourceRewriter.set(initializer, ClassInstanceCreation.TYPE_PROPERTY, targetType, null);
			sourceRewriter.set(extractedReferenceFragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, initializer, null);
		}
		else {
			ExpressionExtractor expressionExtractor = new ExpressionExtractor();
			for(MethodDeclaration constructor : constructorFinalFieldAssignmentMap.keySet()) {
				ASTRewrite constructorRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
				ListRewrite constructorBodyStatementsRewrite = constructorRewriter.getListRewrite(constructor.getBody(), Block.STATEMENTS_PROPERTY);

				Assignment extractedTypeFieldReferenceAssignment = contextAST.newAssignment();
				FieldAccess extractedTypeFieldAccess = contextAST.newFieldAccess();
				constructorRewriter.set(extractedTypeFieldAccess, FieldAccess.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
				constructorRewriter.set(extractedTypeFieldAccess, FieldAccess.EXPRESSION_PROPERTY, contextAST.newThisExpression(), null);
				constructorRewriter.set(extractedTypeFieldReferenceAssignment, Assignment.LEFT_HAND_SIDE_PROPERTY, extractedTypeFieldAccess, null);
				constructorRewriter.set(extractedTypeFieldReferenceAssignment, Assignment.OPERATOR_PROPERTY, Assignment.Operator.ASSIGN, null);
				ClassInstanceCreation classInstanceCreation = contextAST.newClassInstanceCreation();
				Type targetType = contextAST.newSimpleType(contextAST.newName(extractedTypeName));
				constructorRewriter.set(classInstanceCreation, ClassInstanceCreation.TYPE_PROPERTY, targetType, null);
				constructorRewriter.set(extractedTypeFieldReferenceAssignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, classInstanceCreation, null);
				ExpressionStatement assignmentStatement = contextAST.newExpressionStatement(extractedTypeFieldReferenceAssignment);

				Map<VariableDeclaration, Assignment> finalFieldAssignmentMap = constructorFinalFieldAssignmentMap.get(constructor);
				ListRewrite classInstanceCreationArgumentsRewrite = constructorRewriter.getListRewrite(classInstanceCreation, ClassInstanceCreation.ARGUMENTS_PROPERTY);
				Set<VariableDeclaration> extractedClassConstructorParameters = new LinkedHashSet<VariableDeclaration>();

				StatementExtractor statementExtractor = new StatementExtractor();
				List<Statement> variableDeclarationStatements = statementExtractor.getVariableDeclarationStatements(constructor.getBody());
				List<Statement> insertAfterStatements = new ArrayList<Statement>();
				for(VariableDeclaration fieldFragment : finalFieldAssignmentMap.keySet()) {
					Assignment fieldAssignment = finalFieldAssignmentMap.get(fieldFragment);
					List<Expression> variableInstructions = expressionExtractor.getVariableInstructions(fieldAssignment.getRightHandSide());
					TypeVisitor typeVisitor = new TypeVisitor();
					fieldAssignment.getRightHandSide().accept(typeVisitor);
					RefactoringUtility.getSimpleTypeBindings(typeVisitor.getTypeBindings(), requiredImportDeclarationsInExtractedClass);
					for(Expression expression : variableInstructions) {
						SimpleName simpleName = (SimpleName)expression;
						boolean foundInOriginalConstructorParameters = false;
						List<SingleVariableDeclaration> originalConstructorParameters = constructor.parameters();
						for(SingleVariableDeclaration originalConstructorParameter : originalConstructorParameters) {
							if(originalConstructorParameter.resolveBinding().isEqualTo(simpleName.resolveBinding())) {
								if(!extractedClassConstructorParameters.contains(originalConstructorParameter)) {
									classInstanceCreationArgumentsRewrite.insertLast(simpleName, null);
									extractedClassConstructorParameters.add(originalConstructorParameter);
									Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
									typeBindings.add(originalConstructorParameter.getType().resolveBinding());
									RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
									foundInOriginalConstructorParameters = true;
									break;
								}
							}
						}
						if(!foundInOriginalConstructorParameters) {
							boolean foundInVariableDeclarationStatement = false;
							for(Statement statement : variableDeclarationStatements) {
								VariableDeclarationStatement variableDeclarationStatement = (VariableDeclarationStatement)statement;
								List<VariableDeclarationFragment> fragments = variableDeclarationStatement.fragments();
								for(VariableDeclarationFragment fragment : fragments) {
									if(fragment.resolveBinding().isEqualTo(simpleName.resolveBinding())) {
										if(!extractedClassConstructorParameters.contains(fragment)) {
											classInstanceCreationArgumentsRewrite.insertLast(simpleName, null);
											extractedClassConstructorParameters.add(fragment);
											Set<ITypeBinding> typeBindings = new LinkedHashSet<ITypeBinding>();
											typeBindings.add(variableDeclarationStatement.getType().resolveBinding());
											RefactoringUtility.getSimpleTypeBindings(typeBindings, requiredImportDeclarationsInExtractedClass);
											if(!insertAfterStatements.contains(variableDeclarationStatement)) {
												insertAfterStatements.add(variableDeclarationStatement);
											}
											foundInVariableDeclarationStatement = true;
											break;
										}
									}
								}
								if(foundInVariableDeclarationStatement) {
									break;
								}
							}
						}
					}
				}
				if(!insertAfterStatements.isEmpty()) {
					Statement lastStatement = insertAfterStatements.get(0);
					int maxStartPosition = lastStatement.getStartPosition();
					for(int i=1; i<insertAfterStatements.size(); i++) {
						Statement currentStatement = insertAfterStatements.get(i);
						if(currentStatement.getStartPosition() > maxStartPosition) {
							maxStartPosition = currentStatement.getStartPosition();
							lastStatement = currentStatement;
						}
					}
					constructorBodyStatementsRewrite.insertAfter(assignmentStatement, lastStatement, null);
				}
				else {
					constructorBodyStatementsRewrite.insertFirst(assignmentStatement, null);
				}
				extractedClassConstructorParameterMap.put(constructor, extractedClassConstructorParameters);
				try {
					TextEdit sourceEdit = constructorRewriter.rewriteAST();
					ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
					CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
					change.getEdit().addChild(sourceEdit);
					change.addTextEditGroup(new TextEditGroup("Initialize field holding a reference to the extracted class", new TextEdit[] {sourceEdit}));
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		}
		FieldDeclaration extractedReferenceFieldDeclaration = contextAST.newFieldDeclaration(extractedReferenceFragment);
		sourceRewriter.set(extractedReferenceFieldDeclaration, FieldDeclaration.TYPE_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
		ListRewrite typeFieldDeclarationModifiersRewrite = sourceRewriter.getListRewrite(extractedReferenceFieldDeclaration, FieldDeclaration.MODIFIERS2_PROPERTY);
		typeFieldDeclarationModifiersRewrite.insertLast(contextAST.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD), null);
		ITypeBinding serializableTypeBinding = implementsSerializableInterface(sourceTypeDeclaration.resolveBinding());
		if(serializableTypeBinding != null && !existsNonTransientExtractedFieldFragment()) {
			typeFieldDeclarationModifiersRewrite.insertLast(contextAST.newModifier(Modifier.ModifierKeyword.TRANSIENT_KEYWORD), null);
			updateReadObjectInSourceClass(modifiedExtractedTypeName);
			updateWriteObjectInSourceClass(modifiedExtractedTypeName);
		}
		updateCloneInSourceClass(modifiedExtractedTypeName);
		contextBodyRewrite.insertFirst(extractedReferenceFieldDeclaration, null);

		try {
			TextEdit sourceEdit = sourceRewriter.rewriteAST();
			ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
			CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
			change.getEdit().addChild(sourceEdit);
			change.addTextEditGroup(new TextEditGroup("Create field holding a reference to the extracted class", new TextEdit[] {sourceEdit}));
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
	}

	private void updateWriteObjectInSourceClass(String modifiedExtractedTypeName) {
		ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		AST contextAST = sourceTypeDeclaration.getAST();
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		boolean methodFound = false;
		for(MethodDeclaration method : methodDeclarations) {
			if(isWriteObject(method)) {
				methodFound = true;
				if(!extractedMethods.contains(method)) {
					Block methodBody = method.getBody();
					if(methodBody != null) {
						List<SingleVariableDeclaration> parameters = method.parameters();
						SimpleName parameterSimpleName = parameters.get(0).getName();
						ListRewrite statementRewrite = sourceRewriter.getListRewrite(methodBody, Block.STATEMENTS_PROPERTY);
						Statement methodInvocationStatement = createMethodInvocationStatementForWriteObject(
								sourceRewriter, contextAST, modifiedExtractedTypeName, parameterSimpleName);
						Statement firstStatement = isFirstStatementMethodInvocationExpressionStatementWithName(method, "defaultWriteObject");
						if(firstStatement != null) {
							statementRewrite.insertAfter(methodInvocationStatement, firstStatement, null);
						}
						else {
							statementRewrite.insertFirst(methodInvocationStatement, null);
						}
						try {
							TextEdit sourceEdit = sourceRewriter.rewriteAST();
							ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
							CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
							change.getEdit().addChild(sourceEdit);
							change.addTextEditGroup(new TextEditGroup("Update writeObject()", new TextEdit[] {sourceEdit}));
						} catch (JavaModelException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		if(!methodFound) {
			MethodDeclaration writeObjectMethod = contextAST.newMethodDeclaration();
			sourceRewriter.set(writeObjectMethod, MethodDeclaration.NAME_PROPERTY, contextAST.newSimpleName("writeObject"), null);
			ListRewrite parametersRewrite = sourceRewriter.getListRewrite(writeObjectMethod, MethodDeclaration.PARAMETERS_PROPERTY);
			SingleVariableDeclaration parameter = contextAST.newSingleVariableDeclaration();
			Type parameterType = contextAST.newSimpleType(contextAST.newName("java.io.ObjectOutputStream"));
			sourceRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, parameterType, null);
			SimpleName parameterSimpleName = contextAST.newSimpleName("stream");
			sourceRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, parameterSimpleName, null);
			parametersRewrite.insertLast(parameter, null);
			ListRewrite modifiersRewrite = sourceRewriter.getListRewrite(writeObjectMethod, MethodDeclaration.MODIFIERS2_PROPERTY);
			modifiersRewrite.insertLast(contextAST.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD), null);
			sourceRewriter.set(writeObjectMethod, MethodDeclaration.RETURN_TYPE2_PROPERTY, contextAST.newPrimitiveType(PrimitiveType.VOID), null);
			ListRewrite thrownExceptionTypesRewrite = sourceRewriter.getListRewrite(writeObjectMethod, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
			thrownExceptionTypesRewrite.insertLast(contextAST.newSimpleType(contextAST.newName("java.io.IOException")), null);

			Block methodBody = contextAST.newBlock();
			sourceRewriter.set(writeObjectMethod, MethodDeclaration.BODY_PROPERTY, methodBody, null);
			ListRewrite statementRewrite = sourceRewriter.getListRewrite(methodBody, Block.STATEMENTS_PROPERTY);

			MethodInvocation methodInvocation = contextAST.newMethodInvocation();
			sourceRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName("defaultWriteObject"), null);
			sourceRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterSimpleName, null);
			ExpressionStatement methodInvocationStatement = contextAST.newExpressionStatement(methodInvocation);
			statementRewrite.insertLast(methodInvocationStatement, null);

			Statement methodInvocationStatement2 = createMethodInvocationStatementForWriteObject(
					sourceRewriter, contextAST, modifiedExtractedTypeName, parameterSimpleName);
			statementRewrite.insertLast(methodInvocationStatement2, null);

			ListRewrite contextBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
			contextBodyRewrite.insertLast(writeObjectMethod, null);
			try {
				TextEdit sourceEdit = sourceRewriter.rewriteAST();
				ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
				CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
				change.getEdit().addChild(sourceEdit);
				change.addTextEditGroup(new TextEditGroup("Create writeObject()", new TextEdit[] {sourceEdit}));
			} catch (JavaModelException e) {
				e.printStackTrace();
			}
		}
	}

	private Statement createMethodInvocationStatementForWriteObject(ASTRewrite sourceRewriter, AST contextAST,
																	String modifiedExtractedTypeName, SimpleName parameterSimpleName) {
		FieldAccess fieldAccess = contextAST.newFieldAccess();
		sourceRewriter.set(fieldAccess, FieldAccess.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
		sourceRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, contextAST.newThisExpression(), null);

		MethodInvocation writeObjectMethodInvocation = contextAST.newMethodInvocation();
		sourceRewriter.set(writeObjectMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterSimpleName, null);
		sourceRewriter.set(writeObjectMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName("writeObject"), null);
		ListRewrite argumentRewrite = sourceRewriter.getListRewrite(writeObjectMethodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
		argumentRewrite.insertLast(fieldAccess, null);

		Statement methodInvocationStatement = contextAST.newExpressionStatement(writeObjectMethodInvocation);
		return methodInvocationStatement;
	}

	private void updateReadObjectInSourceClass(String modifiedExtractedTypeName) {
		ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		AST contextAST = sourceTypeDeclaration.getAST();
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		boolean methodFound = false;
		for(MethodDeclaration method : methodDeclarations) {
			if(isReadObject(method)) {
				methodFound = true;
				if(!extractedMethods.contains(method)) {
					Block methodBody = method.getBody();
					if(methodBody != null) {
						List<SingleVariableDeclaration> parameters = method.parameters();
						SimpleName parameterSimpleName = parameters.get(0).getName();
						ListRewrite statementRewrite = sourceRewriter.getListRewrite(methodBody, Block.STATEMENTS_PROPERTY);
						Statement assignmentStatement = createAssignmentStatementForReadObject(sourceRewriter,
								contextAST, modifiedExtractedTypeName, parameterSimpleName);
						Statement firstStatement = isFirstStatementMethodInvocationExpressionStatementWithName(method, "defaultReadObject");
						if(firstStatement != null) {
							statementRewrite.insertAfter(assignmentStatement, firstStatement, null);
						}
						else {
							statementRewrite.insertFirst(assignmentStatement, null);
						}
						try {
							TextEdit sourceEdit = sourceRewriter.rewriteAST();
							ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
							CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
							change.getEdit().addChild(sourceEdit);
							change.addTextEditGroup(new TextEditGroup("Update readObject()", new TextEdit[] {sourceEdit}));
						} catch (JavaModelException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
		if(!methodFound) {
			MethodDeclaration readObjectMethod = contextAST.newMethodDeclaration();
			sourceRewriter.set(readObjectMethod, MethodDeclaration.NAME_PROPERTY, contextAST.newSimpleName("readObject"), null);
			ListRewrite parametersRewrite = sourceRewriter.getListRewrite(readObjectMethod, MethodDeclaration.PARAMETERS_PROPERTY);
			SingleVariableDeclaration parameter = contextAST.newSingleVariableDeclaration();
			Type parameterType = contextAST.newSimpleType(contextAST.newName("java.io.ObjectInputStream"));
			sourceRewriter.set(parameter, SingleVariableDeclaration.TYPE_PROPERTY, parameterType, null);
			SimpleName parameterSimpleName = contextAST.newSimpleName("stream");
			sourceRewriter.set(parameter, SingleVariableDeclaration.NAME_PROPERTY, parameterSimpleName, null);
			parametersRewrite.insertLast(parameter, null);
			ListRewrite modifiersRewrite = sourceRewriter.getListRewrite(readObjectMethod, MethodDeclaration.MODIFIERS2_PROPERTY);
			modifiersRewrite.insertLast(contextAST.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD), null);
			sourceRewriter.set(readObjectMethod, MethodDeclaration.RETURN_TYPE2_PROPERTY, contextAST.newPrimitiveType(PrimitiveType.VOID), null);
			ListRewrite thrownExceptionTypesRewrite = sourceRewriter.getListRewrite(readObjectMethod, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
			thrownExceptionTypesRewrite.insertLast(contextAST.newSimpleType(contextAST.newName("java.io.IOException")), null);
			thrownExceptionTypesRewrite.insertLast(contextAST.newSimpleType(contextAST.newName("java.lang.ClassNotFoundException")), null);

			Block methodBody = contextAST.newBlock();
			sourceRewriter.set(readObjectMethod, MethodDeclaration.BODY_PROPERTY, methodBody, null);
			ListRewrite statementRewrite = sourceRewriter.getListRewrite(methodBody, Block.STATEMENTS_PROPERTY);

			MethodInvocation methodInvocation = contextAST.newMethodInvocation();
			sourceRewriter.set(methodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName("defaultReadObject"), null);
			sourceRewriter.set(methodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterSimpleName, null);
			ExpressionStatement methodInvocationStatement = contextAST.newExpressionStatement(methodInvocation);
			statementRewrite.insertLast(methodInvocationStatement, null);
			Statement assignmentStatement = createAssignmentStatementForReadObject(sourceRewriter,
					contextAST, modifiedExtractedTypeName, parameterSimpleName);
			statementRewrite.insertLast(assignmentStatement, null);

			ListRewrite contextBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
			contextBodyRewrite.insertLast(readObjectMethod, null);
			try {
				TextEdit sourceEdit = sourceRewriter.rewriteAST();
				ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
				CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
				change.getEdit().addChild(sourceEdit);
				change.addTextEditGroup(new TextEditGroup("Create readObject()", new TextEdit[] {sourceEdit}));
			} catch (JavaModelException e) {
				e.printStackTrace();
			}
		}
	}

	private Statement createAssignmentStatementForReadObject(ASTRewrite sourceRewriter, AST contextAST,
															 String modifiedExtractedTypeName, SimpleName parameterSimpleName) {
		Assignment assignment = contextAST.newAssignment();
		FieldAccess fieldAccess = contextAST.newFieldAccess();
		sourceRewriter.set(fieldAccess, FieldAccess.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
		sourceRewriter.set(fieldAccess, FieldAccess.EXPRESSION_PROPERTY, contextAST.newThisExpression(), null);
		sourceRewriter.set(assignment, Assignment.LEFT_HAND_SIDE_PROPERTY, fieldAccess, null);

		MethodInvocation readObjectMethodInvocation = contextAST.newMethodInvocation();
		sourceRewriter.set(readObjectMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, parameterSimpleName, null);
		sourceRewriter.set(readObjectMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName("readObject"), null);

		CastExpression castExpression = contextAST.newCastExpression();
		sourceRewriter.set(castExpression, CastExpression.EXPRESSION_PROPERTY, readObjectMethodInvocation, null);
		sourceRewriter.set(castExpression, CastExpression.TYPE_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
		sourceRewriter.set(assignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, castExpression, null);

		Statement assignmentStatement = contextAST.newExpressionStatement(assignment);
		return assignmentStatement;
	}

	private void updateCloneInSourceClass(String modifiedExtractedTypeName) {
		ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
		AST contextAST = sourceTypeDeclaration.getAST();
		MethodDeclaration[] methodDeclarations = sourceTypeDeclaration.getMethods();
		boolean methodFound = false;
		for(MethodDeclaration method : methodDeclarations) {
			if(isClone(method)) {
				methodFound = true;
				if(!extractedMethods.contains(method)) {
					Block methodBody = method.getBody();
					if(methodBody != null) {
						ListRewrite statementRewrite = sourceRewriter.getListRewrite(methodBody, Block.STATEMENTS_PROPERTY);
						VariableDeclarationStatement firstStatement = isFirstStatementVariableDeclarationStatementWithSuperCloneInitializer(method);
						if(firstStatement != null) {
							VariableDeclarationFragment fragment = (VariableDeclarationFragment)firstStatement.fragments().get(0);

							Statement assignmentStatement = createAssignmentStatementForClone(sourceRewriter, contextAST,
									fragment.getName().getIdentifier(), modifiedExtractedTypeName);
							statementRewrite.insertAfter(assignmentStatement, firstStatement, null);

							try {
								TextEdit sourceEdit = sourceRewriter.rewriteAST();
								ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
								CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
								change.getEdit().addChild(sourceEdit);
								change.addTextEditGroup(new TextEditGroup("Update clone()", new TextEdit[] {sourceEdit}));
							} catch (JavaModelException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
		}
		if(!methodFound) {
			IMethodBinding cloneMethodBinding = findCloneMethod(sourceTypeDeclaration.resolveBinding().getSuperclass());
			if(cloneMethodBinding != null) {
				MethodDeclaration cloneMethodDeclaration = contextAST.newMethodDeclaration();
				sourceRewriter.set(cloneMethodDeclaration, MethodDeclaration.NAME_PROPERTY,
						contextAST.newSimpleName(cloneMethodBinding.getName()), null);
				sourceRewriter.set(cloneMethodDeclaration, MethodDeclaration.RETURN_TYPE2_PROPERTY,
						RefactoringUtility.generateTypeFromTypeBinding(cloneMethodBinding.getReturnType(), contextAST, sourceRewriter), null);

				ListRewrite modifiersRewrite = sourceRewriter.getListRewrite(cloneMethodDeclaration, MethodDeclaration.MODIFIERS2_PROPERTY);
				modifiersRewrite.insertLast(contextAST.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD), null);

				ListRewrite thrownExceptionTypesRewrite = sourceRewriter.getListRewrite(cloneMethodDeclaration, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
				ITypeBinding[] thrownExceptionTypeBindings = cloneMethodBinding.getExceptionTypes();
				for(ITypeBinding typeBinding : thrownExceptionTypeBindings) {
					Type type = RefactoringUtility.generateQualifiedTypeFromTypeBinding(typeBinding, contextAST, sourceRewriter);
					thrownExceptionTypesRewrite.insertLast(type, null);
				}

				Block body = contextAST.newBlock();
				sourceRewriter.set(cloneMethodDeclaration, MethodDeclaration.BODY_PROPERTY, body, null);

				ListRewrite statementsRewrite = sourceRewriter.getListRewrite(body, Block.STATEMENTS_PROPERTY);

				VariableDeclarationFragment fragment = contextAST.newVariableDeclarationFragment();
				SimpleName cloneSimpleName = contextAST.newSimpleName("clone");
				Type sourceClassType = RefactoringUtility.generateTypeFromTypeBinding(sourceTypeDeclaration.resolveBinding(), contextAST, sourceRewriter);
				sourceRewriter.set(fragment, VariableDeclarationFragment.NAME_PROPERTY, cloneSimpleName, null);

				SuperMethodInvocation superCloneInvocation = contextAST.newSuperMethodInvocation();
				sourceRewriter.set(superCloneInvocation, SuperMethodInvocation.NAME_PROPERTY, cloneSimpleName, null);
				CastExpression castExpression = contextAST.newCastExpression();
				sourceRewriter.set(castExpression, CastExpression.EXPRESSION_PROPERTY, superCloneInvocation, null);
				sourceRewriter.set(castExpression, CastExpression.TYPE_PROPERTY, sourceClassType, null);
				sourceRewriter.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, castExpression, null);
				VariableDeclarationStatement variableDeclarationStatement = contextAST.newVariableDeclarationStatement(fragment);
				sourceRewriter.set(variableDeclarationStatement, VariableDeclarationStatement.TYPE_PROPERTY, sourceClassType, null);
				statementsRewrite.insertLast(variableDeclarationStatement, null);

				Statement assignmentStatement = createAssignmentStatementForClone(sourceRewriter, contextAST, "clone", modifiedExtractedTypeName);
				statementsRewrite.insertLast(assignmentStatement, null);

				ReturnStatement returnStatement = contextAST.newReturnStatement();
				sourceRewriter.set(returnStatement, ReturnStatement.EXPRESSION_PROPERTY, contextAST.newSimpleName("clone"), null);
				statementsRewrite.insertLast(returnStatement, null);

				ListRewrite contextBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
				contextBodyRewrite.insertLast(cloneMethodDeclaration, null);

				try {
					TextEdit sourceEdit = sourceRewriter.rewriteAST();
					ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
					CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
					change.getEdit().addChild(sourceEdit);
					change.addTextEditGroup(new TextEditGroup("Create clone()", new TextEdit[] {sourceEdit}));
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private Statement createAssignmentStatementForClone(ASTRewrite sourceRewriter, AST contextAST,
														String cloneVariableName, String modifiedExtractedTypeName) {
		Assignment assignment = contextAST.newAssignment();
		FieldAccess cloneFieldAccess = contextAST.newFieldAccess();
		sourceRewriter.set(cloneFieldAccess, FieldAccess.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
		sourceRewriter.set(cloneFieldAccess, FieldAccess.EXPRESSION_PROPERTY, contextAST.newSimpleName(cloneVariableName), null);
		sourceRewriter.set(assignment, Assignment.LEFT_HAND_SIDE_PROPERTY, cloneFieldAccess, null);

		FieldAccess thisFieldAccess = contextAST.newFieldAccess();
		sourceRewriter.set(thisFieldAccess, FieldAccess.NAME_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
		sourceRewriter.set(thisFieldAccess, FieldAccess.EXPRESSION_PROPERTY, contextAST.newThisExpression(), null);

		MethodInvocation cloneInvocation = contextAST.newMethodInvocation();
		sourceRewriter.set(cloneInvocation, MethodInvocation.EXPRESSION_PROPERTY, thisFieldAccess, null);
		sourceRewriter.set(cloneInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName("clone"), null);

		CastExpression castExpression = contextAST.newCastExpression();
		sourceRewriter.set(castExpression, CastExpression.EXPRESSION_PROPERTY, cloneInvocation, null);
		sourceRewriter.set(castExpression, CastExpression.TYPE_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
		sourceRewriter.set(assignment, Assignment.RIGHT_HAND_SIDE_PROPERTY, castExpression, null);

		Statement assignmentStatement = contextAST.newExpressionStatement(assignment);
		return assignmentStatement;
	}

	private boolean isWriteObject(MethodDeclaration method) {
		return method.getName().getIdentifier().equals("writeObject") && method.parameters().size() == 1 &&
				((SingleVariableDeclaration)method.parameters().get(0)).getType().resolveBinding().getQualifiedName().equals("java.io.ObjectOutputStream");
	}

	private boolean isReadObject(MethodDeclaration method) {
		return method.getName().getIdentifier().equals("readObject") && method.parameters().size() == 1 &&
				((SingleVariableDeclaration)method.parameters().get(0)).getType().resolveBinding().getQualifiedName().equals("java.io.ObjectInputStream");
	}

	private boolean isClone(MethodDeclaration method) {
		return method.getName().getIdentifier().equals("clone") && method.parameters().size() == 0 &&
				method.getReturnType2().resolveBinding().getQualifiedName().equals("java.lang.Object");
	}

	private IMethodBinding findCloneMethod(ITypeBinding typeBinding) {
		if(typeBinding != null && !typeBinding.getQualifiedName().equals("java.lang.Object")) {
			for(IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
				if(isClone(methodBinding)) {
					return methodBinding;
				}
			}
			return findCloneMethod(typeBinding.getSuperclass());
		}
		return null;
	}

	private boolean isClone(IMethodBinding methodBinding) {
		return methodBinding.getName().equals("clone") && methodBinding.getParameterTypes().length == 0 &&
				methodBinding.getReturnType().getQualifiedName().equals("java.lang.Object");
	}

	private Statement isFirstStatementMethodInvocationExpressionStatementWithName(MethodDeclaration method, String methodName) {
		Block methodBody = method.getBody();
		if(methodBody != null) {
			List<Statement> statements = methodBody.statements();
			if(!statements.isEmpty()) {
				Statement firstStatement = statements.get(0);
				if(firstStatement instanceof ExpressionStatement) {
					ExpressionStatement expressionStatement = (ExpressionStatement)firstStatement;
					Expression expression = expressionStatement.getExpression();
					if(expression instanceof MethodInvocation) {
						MethodInvocation methodInvocation = (MethodInvocation)expression;
						if(methodInvocation.getName().getIdentifier().equals(methodName)) {
							return firstStatement;
						}
					}
				}
			}
		}
		return null;
	}

	private VariableDeclarationStatement isFirstStatementVariableDeclarationStatementWithSuperCloneInitializer(MethodDeclaration method) {
		Block methodBody = method.getBody();
		if(methodBody != null) {
			List<Statement> statements = methodBody.statements();
			if(!statements.isEmpty()) {
				Statement firstStatement = statements.get(0);
				if(firstStatement instanceof VariableDeclarationStatement) {
					VariableDeclarationStatement variableDeclarationStatement = (VariableDeclarationStatement)firstStatement;
					List<VariableDeclarationFragment> fragments = variableDeclarationStatement.fragments();
					if(fragments.size() == 1) {
						for(VariableDeclarationFragment fragment : fragments) {
							if(fragment.getInitializer() != null) {
								Expression expression = fragment.getInitializer();
								if(expression instanceof CastExpression) {
									CastExpression castExpression = (CastExpression)expression;
									if(castExpression.getExpression() instanceof SuperMethodInvocation) {
										SuperMethodInvocation superMethodInvocation = (SuperMethodInvocation)castExpression.getExpression();
										if(superMethodInvocation.getName().getIdentifier().equals("clone") &&
												superMethodInvocation.arguments().size() == 0) {
											return variableDeclarationStatement;
										}
									}
								}
							}
						}
					}
				}
			}
		}
		return null;
	}

	private void removeFieldFragmentsInSourceClass(Set<VariableDeclaration> fieldFragments) {
		FieldDeclaration[] fieldDeclarations = sourceTypeDeclaration.getFields();
		for(FieldDeclaration fieldDeclaration : fieldDeclarations) {
			List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
			int actualNumberOfFragments = fragments.size();
			Set<VariableDeclaration> fragmentsToBeRemoved = new LinkedHashSet<VariableDeclaration>();
			for(VariableDeclarationFragment fragment : fragments) {
				if(fieldFragments.contains(fragment)) {
					fragmentsToBeRemoved.add(fragment);
				}
			}
			if(fragmentsToBeRemoved.size() > 0) {
				ASTRewrite sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
				ListRewrite contextBodyRewrite = sourceRewriter.getListRewrite(sourceTypeDeclaration, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
				if(actualNumberOfFragments == fragmentsToBeRemoved.size()) {
					contextBodyRewrite.remove(fieldDeclaration, null);
				}
				else if(fragmentsToBeRemoved.size() < actualNumberOfFragments) {
					ListRewrite fragmentRewrite = sourceRewriter.getListRewrite(fieldDeclaration, FieldDeclaration.FRAGMENTS_PROPERTY);
					for(VariableDeclaration fragment : fragmentsToBeRemoved) {
						fragmentRewrite.remove(fragment, null);
					}
				}
				try {
					TextEdit sourceEdit = sourceRewriter.rewriteAST();
					ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
					CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
					change.getEdit().addChild(sourceEdit);
					change.addTextEditGroup(new TextEditGroup("Remove extracted field", new TextEdit[] {sourceEdit}));
				} catch (JavaModelException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void modifyExtractedFieldAssignmentsInSourceClass(Set<VariableDeclaration> fieldFragments, Set<VariableDeclaration> modifiedFields, Set<VariableDeclaration> accessedFields) {
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		Set<MethodDeclaration> contextMethods = getAllMethodDeclarationsInSourceClass();
		String modifiedExtractedTypeName = extractedTypeName.substring(0,1).toLowerCase() + extractedTypeName.substring(1,extractedTypeName.length());
		for(MethodDeclaration methodDeclaration : contextMethods) {
			if(!extractedMethods.contains(methodDeclaration)) {
				Block methodBody = methodDeclaration.getBody();
				if(methodBody != null) {
					List<Statement> statements = methodBody.statements();
					for(Statement statement : statements) {
						ASTRewrite sourceRewriter = null;
						if(statementRewriteMap.containsKey(statement)) {
							sourceRewriter = statementRewriteMap.get(statement);
						}
						else {
							sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
						}
						AST contextAST = sourceTypeDeclaration.getAST();
						boolean rewriteAST = false;
						List<Expression> assignments = expressionExtractor.getAssignments(statement);
						for(Expression expression : assignments) {
							Assignment assignment = (Assignment)expression;
							Expression leftHandSide = assignment.getLeftHandSide();
							SimpleName assignedVariable = null;
							if(leftHandSide instanceof SimpleName) {
								assignedVariable = (SimpleName)leftHandSide;
							}
							else if(leftHandSide instanceof FieldAccess) {
								FieldAccess fieldAccess = (FieldAccess)leftHandSide;
								assignedVariable = fieldAccess.getName();
							}
							else if(leftHandSide instanceof QualifiedName) {
								QualifiedName qualifiedName = (QualifiedName)leftHandSide;
								assignedVariable = qualifiedName.getName();
							}
							Expression rightHandSide = assignment.getRightHandSide();
							List<Expression> accessedVariables = expressionExtractor.getVariableInstructions(rightHandSide);
							List<Expression> arrayAccesses = expressionExtractor.getArrayAccesses(leftHandSide);
							for(VariableDeclaration fieldFragment : fieldFragments) {
								String originalFieldName = fieldFragment.getName().getIdentifier();
								String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
								String getterMethodName = GETTER_PREFIX + modifiedFieldName;
								getterMethodName = appendAccessorMethodSuffix(getterMethodName, this.extractedMethods);
								if(assignedVariable != null) {
									IBinding leftHandBinding = assignedVariable.resolveBinding();
									if(leftHandBinding != null && leftHandBinding.getKind() == IBinding.VARIABLE) {
										IVariableBinding assignedVariableBinding = (IVariableBinding)leftHandBinding;
										if(assignedVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(assignedVariableBinding)) {
											if(methodDeclaration.isConstructor() && (assignedVariableBinding.getModifiers() & Modifier.FINAL) != 0) {
												if(assignment.getParent() instanceof ExpressionStatement) {
													ExpressionStatement assignmentStatement = (ExpressionStatement)assignment.getParent();
													ListRewrite constructorStatementsRewrite = sourceRewriter.getListRewrite(methodDeclaration.getBody(), Block.STATEMENTS_PROPERTY);
													constructorStatementsRewrite.remove(assignmentStatement, null);
													if(constructorFinalFieldAssignmentMap.containsKey(methodDeclaration)) {
														Map<VariableDeclaration, Assignment> finalFieldAssignmentMap = constructorFinalFieldAssignmentMap.get(methodDeclaration);
														finalFieldAssignmentMap.put(fieldFragment, assignment);
													}
													else {
														Map<VariableDeclaration, Assignment> finalFieldAssignmentMap = new LinkedHashMap<VariableDeclaration, Assignment>();
														finalFieldAssignmentMap.put(fieldFragment, assignment);
														constructorFinalFieldAssignmentMap.put(methodDeclaration, finalFieldAssignmentMap);
													}
												}
											}
											else {
												MethodInvocation setterMethodInvocation = contextAST.newMethodInvocation();
												String setterMethodName = SETTER_PREFIX + modifiedFieldName;
												setterMethodName = appendAccessorMethodSuffix(setterMethodName, this.extractedMethods);
												sourceRewriter.set(setterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(setterMethodName), null);
												ListRewrite setterMethodInvocationArgumentsRewrite = sourceRewriter.getListRewrite(setterMethodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
												if(!assignment.getOperator().equals(Assignment.Operator.ASSIGN)) {
													accessedFields.add(fieldFragment);
													InfixExpression infixExpression = contextAST.newInfixExpression();
													MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
													if((assignedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
														sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
													}
													else {
														sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
													}
													sourceRewriter.set(infixExpression, InfixExpression.LEFT_OPERAND_PROPERTY, getterMethodInvocation, null);
													sourceRewriter.set(infixExpression, InfixExpression.RIGHT_OPERAND_PROPERTY, assignment.getRightHandSide(), null);
													if(assignment.getOperator().equals(Assignment.Operator.PLUS_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.PLUS, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.MINUS_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.MINUS, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.TIMES_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.TIMES, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.DIVIDE_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.DIVIDE, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.REMAINDER_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.REMAINDER, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.BIT_AND_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.AND, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.BIT_OR_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.OR, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.BIT_XOR_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.XOR, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.LEFT_SHIFT_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.LEFT_SHIFT, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.RIGHT_SHIFT_SIGNED_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.RIGHT_SHIFT_SIGNED, null);
													}
													else if(assignment.getOperator().equals(Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN)) {
														sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.RIGHT_SHIFT_UNSIGNED, null);
													}
													setterMethodInvocationArgumentsRewrite.insertLast(infixExpression, null);
												}
												else {
													setterMethodInvocationArgumentsRewrite.insertLast(assignment.getRightHandSide(), null);
												}
												if(leftHandSide instanceof QualifiedName) {
													Name qualifier = contextAST.newName(((QualifiedName)leftHandSide).getQualifier().getFullyQualifiedName());
													QualifiedName qualifiedName = contextAST.newQualifiedName(qualifier, contextAST.newSimpleName(modifiedExtractedTypeName));
													sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, qualifiedName, null);
												}
												else if((assignedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
													sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
												}
												else {
													sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
												}
												sourceRewriter.replace(assignment, setterMethodInvocation, null);
											}
											rewriteAST = true;
											modifiedFields.add(fieldFragment);
										}
									}
								}
								for(Expression expression2 : arrayAccesses) {
									ArrayAccess arrayAccess = (ArrayAccess)expression2;
									Expression arrayExpression = arrayAccess.getArray();
									SimpleName arrayVariable = null;
									if(arrayExpression instanceof SimpleName) {
										arrayVariable = (SimpleName)arrayExpression;
									}
									else if(arrayExpression instanceof FieldAccess) {
										FieldAccess fieldAccess = (FieldAccess)arrayExpression;
										arrayVariable = fieldAccess.getName();
									}
									else if(arrayExpression instanceof QualifiedName) {
										QualifiedName qualifiedName = (QualifiedName)arrayExpression;
										arrayVariable = qualifiedName.getName();
									}
									if(arrayVariable != null) {
										IBinding arrayBinding = arrayVariable.resolveBinding();
										if(arrayBinding != null && arrayBinding.getKind() == IBinding.VARIABLE) {
											IVariableBinding arrayVariableBinding = (IVariableBinding)arrayBinding;
											if(arrayVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(arrayVariableBinding)) {
												MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
												if((arrayVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
												}
												else {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
												}
												sourceRewriter.replace(arrayVariable, getterMethodInvocation, null);
												rewriteAST = true;
												accessedFields.add(fieldFragment);
											}
										}
									}
								}
								for(Expression expression2 : accessedVariables) {
									SimpleName accessedVariable = (SimpleName)expression2;
									IBinding rightHandBinding = accessedVariable.resolveBinding();
									if(rightHandBinding != null && rightHandBinding.getKind() == IBinding.VARIABLE) {
										IVariableBinding accessedVariableBinding = (IVariableBinding)rightHandBinding;
										if(accessedVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(accessedVariableBinding)) {
											MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
											sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
											if((accessedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
											}
											else {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
											}
											sourceRewriter.replace(accessedVariable, getterMethodInvocation, null);
											rewriteAST = true;
											accessedFields.add(fieldFragment);
										}
									}
								}
							}
						}
						List<Expression> postfixExpressions = expressionExtractor.getPostfixExpressions(statement);
						for(Expression expression : postfixExpressions) {
							PostfixExpression postfix = (PostfixExpression)expression;
							Expression operand = postfix.getOperand();
							SimpleName assignedVariable = null;
							if(operand instanceof SimpleName) {
								assignedVariable = (SimpleName)operand;
							}
							else if(operand instanceof FieldAccess) {
								FieldAccess fieldAccess = (FieldAccess)operand;
								assignedVariable = fieldAccess.getName();
							}
							else if(operand instanceof QualifiedName) {
								QualifiedName qualifiedName = (QualifiedName)operand;
								assignedVariable = qualifiedName.getName();
							}
							List<Expression> arrayAccesses = expressionExtractor.getArrayAccesses(operand);
							for(VariableDeclaration fieldFragment : fieldFragments) {
								String originalFieldName = fieldFragment.getName().getIdentifier();
								String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
								String getterMethodName = GETTER_PREFIX + modifiedFieldName;
								getterMethodName = appendAccessorMethodSuffix(getterMethodName, this.extractedMethods);
								if(assignedVariable != null) {
									IBinding operandBinding = assignedVariable.resolveBinding();
									if(operandBinding != null && operandBinding.getKind() == IBinding.VARIABLE) {
										IVariableBinding assignedVariableBinding = (IVariableBinding)operandBinding;
										if(assignedVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(assignedVariableBinding)) {
											MethodInvocation setterMethodInvocation = contextAST.newMethodInvocation();
											String setterMethodName = SETTER_PREFIX + modifiedFieldName;
											setterMethodName = appendAccessorMethodSuffix(setterMethodName, this.extractedMethods);
											sourceRewriter.set(setterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(setterMethodName), null);
											ListRewrite setterMethodInvocationArgumentsRewrite = sourceRewriter.getListRewrite(setterMethodInvocation, MethodInvocation.ARGUMENTS_PROPERTY);
											accessedFields.add(fieldFragment);
											InfixExpression infixExpression = contextAST.newInfixExpression();
											MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
											sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
											if((assignedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
											}
											else {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
											}
											sourceRewriter.set(infixExpression, InfixExpression.LEFT_OPERAND_PROPERTY, getterMethodInvocation, null);
											sourceRewriter.set(infixExpression, InfixExpression.RIGHT_OPERAND_PROPERTY, contextAST.newNumberLiteral("1"), null);
											if(postfix.getOperator().equals(PostfixExpression.Operator.INCREMENT)) {
												sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.PLUS, null);
											}
											else if(postfix.getOperator().equals(PostfixExpression.Operator.DECREMENT)) {
												sourceRewriter.set(infixExpression, InfixExpression.OPERATOR_PROPERTY, InfixExpression.Operator.MINUS, null);
											}
											setterMethodInvocationArgumentsRewrite.insertLast(infixExpression, null);
											if(operand instanceof QualifiedName) {
												Name qualifier = contextAST.newName(((QualifiedName)operand).getQualifier().getFullyQualifiedName());
												QualifiedName qualifiedName = contextAST.newQualifiedName(qualifier, contextAST.newSimpleName(modifiedExtractedTypeName));
												sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, qualifiedName, null);
											}
											else if((assignedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
												sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
											}
											else {
												sourceRewriter.set(setterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
											}
											sourceRewriter.replace(postfix, setterMethodInvocation, null);
											rewriteAST = true;
											modifiedFields.add(fieldFragment);
										}
									}
								}
								for(Expression expression2 : arrayAccesses) {
									ArrayAccess arrayAccess = (ArrayAccess)expression2;
									Expression arrayExpression = arrayAccess.getArray();
									SimpleName arrayVariable = null;
									if(arrayExpression instanceof SimpleName) {
										arrayVariable = (SimpleName)arrayExpression;
									}
									else if(arrayExpression instanceof FieldAccess) {
										FieldAccess fieldAccess = (FieldAccess)arrayExpression;
										arrayVariable = fieldAccess.getName();
									}
									else if(arrayExpression instanceof QualifiedName) {
										QualifiedName qualifiedName = (QualifiedName)arrayExpression;
										arrayVariable = qualifiedName.getName();
									}
									if(arrayVariable != null) {
										IBinding arrayBinding = arrayVariable.resolveBinding();
										if(arrayBinding != null && arrayBinding.getKind() == IBinding.VARIABLE) {
											IVariableBinding arrayVariableBinding = (IVariableBinding)arrayBinding;
											if(arrayVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(arrayVariableBinding)) {
												MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
												if((arrayVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
												}
												else {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
												}
												sourceRewriter.replace(arrayVariable, getterMethodInvocation, null);
												rewriteAST = true;
												accessedFields.add(fieldFragment);
											}
										}
									}
								}
							}
						}
						if(rewriteAST) {
							if(!statementRewriteMap.containsKey(statement))
								statementRewriteMap.put(statement, sourceRewriter);
							/*try {
								TextEdit sourceEdit = sourceRewriter.rewriteAST();
								ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
								CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
								change.getEdit().addChild(sourceEdit);
								change.addTextEditGroup(new TextEditGroup("Replace field assignment with invocation of setter method", new TextEdit[] {sourceEdit}));
							} catch (JavaModelException e) {
								e.printStackTrace();
							}*/
						}
					}
				}
			}
		}
	}

	private void modifyExtractedFieldAccessesInSourceClass(Set<VariableDeclaration> fieldFragments, Set<VariableDeclaration> accessedFields) {
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		Set<MethodDeclaration> contextMethods = getAllMethodDeclarationsInSourceClass();
		String modifiedExtractedTypeName = extractedTypeName.substring(0,1).toLowerCase() + extractedTypeName.substring(1,extractedTypeName.length());
		for(MethodDeclaration methodDeclaration : contextMethods) {
			if(!extractedMethods.contains(methodDeclaration)) {
				Block methodBody = methodDeclaration.getBody();
				if(methodBody != null) {
					List<Statement> statements = methodBody.statements();
					for(Statement statement : statements) {
						ASTRewrite sourceRewriter = null;
						if(statementRewriteMap.containsKey(statement)) {
							sourceRewriter = statementRewriteMap.get(statement);
						}
						else {
							sourceRewriter = ASTRewrite.create(sourceTypeDeclaration.getAST());
						}
						AST contextAST = sourceTypeDeclaration.getAST();
						boolean rewriteAST = false;
						List<Expression> accessedVariables = expressionExtractor.getVariableInstructions(statement);
						List<Expression> arrayAccesses = expressionExtractor.getArrayAccesses(statement);
						for(VariableDeclaration fieldFragment : fieldFragments) {
							String originalFieldName = fieldFragment.getName().getIdentifier();
							String modifiedFieldName = originalFieldName.substring(0,1).toUpperCase() + originalFieldName.substring(1,originalFieldName.length());
							String getterMethodName = GETTER_PREFIX + modifiedFieldName;
							getterMethodName = appendAccessorMethodSuffix(getterMethodName, this.extractedMethods);
							for(Expression expression : accessedVariables) {
								SimpleName accessedVariable = (SimpleName)expression;
								IBinding binding = accessedVariable.resolveBinding();
								if(binding != null && binding.getKind() == IBinding.VARIABLE) {
									IVariableBinding accessedVariableBinding = (IVariableBinding)binding;
									if(accessedVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(accessedVariableBinding)) {
										if(!isAssignmentChild(expression)) {
											MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
											sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
											if((accessedVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
											}
											else {
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
											}
											sourceRewriter.replace(accessedVariable, getterMethodInvocation, null);
											rewriteAST = true;
											accessedFields.add(fieldFragment);
										}
									}
								}
							}
							for(Expression expression : arrayAccesses) {
								ArrayAccess arrayAccess = (ArrayAccess)expression;
								Expression arrayExpression = arrayAccess.getArray();
								SimpleName arrayVariable = null;
								if(arrayExpression instanceof SimpleName) {
									arrayVariable = (SimpleName)arrayExpression;
								}
								else if(arrayExpression instanceof FieldAccess) {
									FieldAccess fieldAccess = (FieldAccess)arrayExpression;
									arrayVariable = fieldAccess.getName();
								}
								if(arrayVariable != null) {
									IBinding arrayBinding = arrayVariable.resolveBinding();
									if(arrayBinding != null && arrayBinding.getKind() == IBinding.VARIABLE) {
										IVariableBinding arrayVariableBinding = (IVariableBinding)arrayBinding;
										if(arrayVariableBinding.isField() && fieldFragment.resolveBinding().isEqualTo(arrayVariableBinding)) {
											if(!isAssignmentChild(expression)) {
												MethodInvocation getterMethodInvocation = contextAST.newMethodInvocation();
												sourceRewriter.set(getterMethodInvocation, MethodInvocation.NAME_PROPERTY, contextAST.newSimpleName(getterMethodName), null);
												if((arrayVariableBinding.getModifiers() & Modifier.STATIC) != 0) {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(extractedTypeName), null);
												}
												else {
													sourceRewriter.set(getterMethodInvocation, MethodInvocation.EXPRESSION_PROPERTY, contextAST.newSimpleName(modifiedExtractedTypeName), null);
												}
												sourceRewriter.replace(arrayVariable, getterMethodInvocation, null);
												rewriteAST = true;
												accessedFields.add(fieldFragment);
											}
										}
									}
								}
							}
						}
						if(rewriteAST) {
							if(!statementRewriteMap.containsKey(statement))
								statementRewriteMap.put(statement, sourceRewriter);
							/*try {
								TextEdit sourceEdit = sourceRewriter.rewriteAST();
								ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
								CompilationUnitChange change = compilationUnitChanges.get(sourceICompilationUnit);
								change.getEdit().addChild(sourceEdit);
								change.addTextEditGroup(new TextEditGroup("Replace field access with invocation of getter method", new TextEdit[] {sourceEdit}));
							} catch (JavaModelException e) {
								e.printStackTrace();
							}*/
						}
					}
				}
			}
		}
	}

	private Set<MethodDeclaration> getAllMethodDeclarationsInSourceClass() {
		Set<MethodDeclaration> contextMethods = new LinkedHashSet<MethodDeclaration>();
		for(FieldDeclaration fieldDeclaration : sourceTypeDeclaration.getFields()) {
			contextMethods.addAll(getMethodDeclarationsWithinAnonymousClassDeclarations(fieldDeclaration));
		}
		List<MethodDeclaration> methodDeclarationList = Arrays.asList(sourceTypeDeclaration.getMethods());
		contextMethods.addAll(methodDeclarationList);
		/*for(MethodDeclaration methodDeclaration : methodDeclarationList) {
			contextMethods.addAll(getMethodDeclarationsWithinAnonymousClassDeclarations(methodDeclaration));
		}*/
		//get methods of inner classes
		TypeDeclaration[] types = sourceTypeDeclaration.getTypes();
		for(TypeDeclaration type : types) {
			for(FieldDeclaration fieldDeclaration : type.getFields()) {
				contextMethods.addAll(getMethodDeclarationsWithinAnonymousClassDeclarations(fieldDeclaration));
			}
			List<MethodDeclaration> innerMethodDeclarationList = Arrays.asList(type.getMethods());
			contextMethods.addAll(innerMethodDeclarationList);
			/*for(MethodDeclaration methodDeclaration : innerMethodDeclarationList) {
				contextMethods.addAll(getMethodDeclarationsWithinAnonymousClassDeclarations(methodDeclaration));
			}*/
		}
		return contextMethods;
	}

	private Set<MethodDeclaration> getMethodDeclarationsWithinAnonymousClassDeclarations(MethodDeclaration methodDeclaration) {
		Set<MethodDeclaration> methods = new LinkedHashSet<MethodDeclaration>();
		ExpressionExtractor expressionExtractor = new ExpressionExtractor();
		List<Expression> classInstanceCreations = expressionExtractor.getClassInstanceCreations(methodDeclaration.getBody());
		for(Expression expression : classInstanceCreations) {
			ClassInstanceCreation classInstanceCreation = (ClassInstanceCreation)expression;
			AnonymousClassDeclaration anonymousClassDeclaration = classInstanceCreation.getAnonymousClassDeclaration();
			if(anonymousClassDeclaration != null) {
				List<BodyDeclaration> bodyDeclarations = anonymousClassDeclaration.bodyDeclarations();
				for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
					if(bodyDeclaration instanceof MethodDeclaration)
						methods.add((MethodDeclaration)bodyDeclaration);
				}
			}
		}
		return methods;
	}

	private Set<MethodDeclaration> getMethodDeclarationsWithinAnonymousClassDeclarations(FieldDeclaration fieldDeclaration) {
		Set<MethodDeclaration> methods = new LinkedHashSet<MethodDeclaration>();
		List<VariableDeclarationFragment> fragments = fieldDeclaration.fragments();
		for(VariableDeclarationFragment fragment : fragments) {
			Expression expression = fragment.getInitializer();
			if(expression != null && expression instanceof ClassInstanceCreation) {
				ClassInstanceCreation classInstanceCreation = (ClassInstanceCreation)expression;
				AnonymousClassDeclaration anonymousClassDeclaration = classInstanceCreation.getAnonymousClassDeclaration();
				if(anonymousClassDeclaration != null) {
					List<BodyDeclaration> bodyDeclarations = anonymousClassDeclaration.bodyDeclarations();
					for(BodyDeclaration bodyDeclaration : bodyDeclarations) {
						if(bodyDeclaration instanceof MethodDeclaration)
							methods.add((MethodDeclaration)bodyDeclaration);
					}
				}
			}
		}
		return methods;
	}

	private boolean isAssignmentChild(ASTNode node) {
		if(node instanceof Assignment)
			return true;
		else if(node instanceof PrefixExpression) {
			PrefixExpression prefixExpression = (PrefixExpression)node;
			if(prefixExpression.getOperator().equals(PrefixExpression.Operator.INCREMENT) ||
					prefixExpression.getOperator().equals(PrefixExpression.Operator.DECREMENT))
				return true;
			else
				return isAssignmentChild(node.getParent());
		}
		else if(node instanceof PostfixExpression) {
			PostfixExpression postfixExpression = (PostfixExpression)node;
			if(postfixExpression.getOperator().equals(PostfixExpression.Operator.INCREMENT) ||
					postfixExpression.getOperator().equals(PostfixExpression.Operator.DECREMENT))
				return true;
			else
				return isAssignmentChild(node.getParent());
		}
		else if(node instanceof Statement)
			return false;
		else
			return isAssignmentChild(node.getParent());
	}

	@Override
	public String getName() {
		return "Extract Class";
	}

	@Override
	public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
			throws CoreException, OperationCanceledException {
		RefactoringStatus status= new RefactoringStatus();
		try {
			pm.beginTask("Checking preconditions...", 1);
		} finally {
			pm.done();
		}
		return status;
	}

	@Override
	public RefactoringStatus checkFinalConditions(IProgressMonitor pm)
			throws CoreException, OperationCanceledException {
		final RefactoringStatus status= new RefactoringStatus();
		try {
			pm.beginTask("Checking preconditions...", 2);
			apply();
		} finally {
			pm.done();
		}
		return status;
	}

	@Override
	public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
		try {
			pm.beginTask("Creating change...", 1);
			final Collection<Change> changes = new ArrayList<Change>();
			changes.addAll(compilationUnitChanges.values());
			changes.addAll(createCompilationUnitChanges.values());
			CompositeChange change = new CompositeChange(getName(), changes.toArray(new Change[changes.size()])) {
				@Override
				public ChangeDescriptor getDescriptor() {
					ICompilationUnit sourceICompilationUnit = (ICompilationUnit)sourceCompilationUnit.getJavaElement();
					String project = sourceICompilationUnit.getJavaProject().getElementName();
					String description = MessageFormat.format("Extracting class from ''{0}''", new Object[] { sourceTypeDeclaration.getName().getIdentifier()});
					String comment = null;
					return new RefactoringChangeDescriptor(new ExtractClassRefactoringDescriptor(project, description, comment,
							sourceCompilationUnit, sourceTypeDeclaration, sourceFile, extractedFieldFragments, extractedMethods, delegateMethods, extractedTypeName));
				}
			};
			return change;
		} finally {
			pm.done();
		}
	}
}
