package de.refactoringbot.refactoring.supportedrefactorings;

import java.io.FileInputStream;
import java.util.List;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
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

		// Variable to delete
		VariableDeclarator variableToDelete = null;
		FieldDeclaration variableField = null;
		ResolvedValueDeclaration resolvedVariable = null;

		// Iterate fields
		for (FieldDeclaration field : fields) {
			System.out.println("");
			System.out.println("Searching field at LINE " + field.getBegin().get().line);
			// Get field variables
			NodeList<VariableDeclarator> variables = field.getVariables();

			// Iterate variables
			for (VariableDeclarator variable : variables) {
				System.out.println("Variable found : " + variable.getNameAsString());
				if (variable.getNameAsString().equals(issue.getRefactorString())) {
					System.out.println("Hier!");
					variableToDelete = variable;
					variableField = field;
					try {
						resolvedVariable = variable.resolve();
					} catch (Exception e) {
						throw new BotRefactoringException("Could not resolve variable!");
					}
				}
			}
		}
		for (FieldAccessExpr expr : exprs) {
			System.out.println("Variable call found! Name: " + expr.getNameAsString() + ", Line: " + expr.getBegin().get().line);
			try {
				if (expr.resolve().equals(resolvedVariable)) {
					System.out.println("Variable Used!");
				}
			} catch (Exception e) {
				continue;
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
