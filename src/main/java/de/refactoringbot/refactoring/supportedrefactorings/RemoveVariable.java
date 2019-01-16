package de.refactoringbot.refactoring.supportedrefactorings;

import java.io.FileInputStream;
import java.util.List;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import de.refactoringbot.model.botissue.BotIssue;
import de.refactoringbot.model.configuration.GitConfiguration;
import de.refactoringbot.model.exceptions.BotRefactoringException;
import de.refactoringbot.refactoring.RefactoringImpl;

public class RemoveVariable implements RefactoringImpl {

	/**
	 * This method performs the refactoring and returns the a commit message.
	 * 
	 * @param issue
	 * @param gitConfig
	 * @return commitMessage
	 * @throws Exception
	 */
	@Override
	public String performRefactoring(BotIssue issue, GitConfiguration gitConfig) throws Exception {

		// Init needed variables
		String issueFilePath = gitConfig.getRepoFolder() + "/" + issue.getFilePath();

		// Configure solver for the project
		CombinedTypeSolver typeSolver = new CombinedTypeSolver();
		// Add java-roots
		for (String javaRoot : issue.getJavaRoots()) {
			typeSolver.add(new JavaParserTypeSolver(javaRoot));
		}
		typeSolver.add(new ReflectionTypeSolver());
		JavaSymbolSolver javaSymbolSolver = new JavaSymbolSolver(typeSolver);
		JavaParser.getStaticConfiguration().setSymbolResolver(javaSymbolSolver);

		// Read file
		FileInputStream variablePath = new FileInputStream(issueFilePath);
		CompilationUnit cu = LexicalPreservingPrinter.setup(JavaParser.parse(variablePath));

		// Get all fields
		List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
		List<FieldAccessExpr> exprs = cu.findAll(FieldAccessExpr.class);
		List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

		// Variable to delete
		VariableDeclarator variableToDelete = null;
		FieldDeclaration variableField = null;

		// Iterate fields
		for (FieldDeclaration field : fields) {
			// Get field variables
			NodeList<VariableDeclarator> variables = field.getVariables();

			// Iterate variables
			for (VariableDeclarator variable : variables) {
				// System.out.println("Variable found : " + variable.getNameAsString());
				if (variable.getNameAsString().equals(issue.getRefactorString()) && variable.getBegin().isPresent()
						&& variable.getBegin().get().line == issue.getLine()) {
					variableToDelete = variable;
					variableField = field;
				}
			}
		}
		for (FieldAccessExpr expr : exprs) {
			List<ThisExpr> thisExprs = expr.findAll(ThisExpr.class);

			System.out.println("");
			System.out.println(
					"Field Access size: " + expr.getChildNodes().size() + " at Line: " + expr.getBegin().get().line
							+ " with scope: " + expr.getScope().toString() + " and name: " + expr.getNameAsString());

			for (Node node : expr.getChildNodes()) {
				System.out.println("Node: '" + node.toString() + "' at Column: " + node.getBegin().get().column);
			}

			for (ThisExpr thisExpr : thisExprs) {
				System.out.println("");
				System.out.println("This Expr Qualified Name: " + thisExpr.resolve().getQualifiedName());
				System.out.println("This Expr Name: " + thisExpr.resolve().getName());
				System.out.println("This Expr ID: " + thisExpr.resolve().getName());
			}
		}

		// Iterate methods
		for (MethodDeclaration method : methods) {
			System.out.println("");
			System.out.println("Method: '" + method.resolve().getQualifiedSignature());
			boolean methodParameterSameName = false;
			if (method.getParameterByName(issue.getRefactorString()).isPresent()) {
				methodParameterSameName = true;
			}

			if (method.getBody().isPresent()) {
				List<AssignExpr> assignments = method.getBody().get().findAll(AssignExpr.class);
				List<ReturnStmt> returnStatement = method.getBody().get().findAll(ReturnStmt.class);
				List<VariableDeclarationExpr> varDecls = method.findAll(VariableDeclarationExpr.class);
				if (returnStatement.size() == 0) {
					System.out.println("No return statement!");
				} else if (returnStatement.size() == 1) {
					System.out.println("Return: " + returnStatement.get(0).toString());
				}
				for (AssignExpr assignment : assignments) {
					System.out.println("'" + assignment.toString() + "' at line: " + assignment.getBegin().get().line);
				}

				for (VariableDeclarationExpr varDecl : varDecls) {
					System.out.println("'" + varDecl.toString() + "' at line: " + varDecl.getBegin().get().line);
				}
			}
		}

		// If variable not found
		if (variableToDelete == null) {
			throw new BotRefactoringException("Variable with given name does not exist at given line!");
		}

		// Remove variable
		variableField.remove(variableToDelete);

		// If field does not contain any more variables
		if (variableField.getVariables().size() == 0) {
			// Remove whole field
			variableField.remove();
		}
		
		throw new BotRefactoringException("Refactoring not finished yet!");
	}

}
