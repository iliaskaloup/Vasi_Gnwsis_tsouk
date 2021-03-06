/*
 * Copyright (c) 2012-2017 The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */
package org.antlr.v4.test.runtime.csharp;

import org.antlr.v4.Tool;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.WritableToken;
import org.antlr.v4.runtime.misc.Utils;
import org.antlr.v4.test.runtime.*;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.GrammarSemanticsMessage;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.stringtemplate.v4.ST;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.antlr.v4.test.runtime.BaseRuntimeTest.antlrOnString;
import static org.antlr.v4.test.runtime.BaseRuntimeTest.writeFile;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BaseCSharpTest implements RuntimeTestSupport {
	public static final String newline = System.getProperty("line.separator");

	/**
	 * When the {@code antlr.preserve-test-dir} runtime property is set to
	 * {@code true}, the temporary directories created by the test run will not
	 * be removed at the end of the test run, even for tests that completed
	 * successfully.
	 *
	 * <p>
	 * The default behavior (used in all other cases) is removing the temporary
	 * directories for all tests which completed successfully, and preserving
	 * the directories for tests which failed.</p>
	 */
	public static final boolean PRESERVE_TEST_DIR = Boolean.parseBoolean(System.getProperty("antlr-preserve-csharp-test-dir"));

	/**
	 * The base test directory is the directory where generated files get placed
	 * during unit test execution.
	 *
	 * <p>
	 * The default value for this property is the {@code java.io.tmpdir} system
	 * property, and can be overridden by setting the
	 * {@code antlr.java-test-dir} property to a custom location. Note that the
	 * {@code antlr.java-test-dir} property directly affects the
	 * {@link #CREATE_PER_TEST_DIRECTORIES} value as well.</p>
	 */
	public static final String BASE_TEST_DIR;

	/**
	 * When {@code true}, a temporary directory will be created for each test
	 * executed during the test run.
	 *
	 * <p>
	 * This value is {@code true} when the {@code antlr.java-test-dir} system
	 * property is set, and otherwise {@code false}.</p>
	 */
	public static final boolean CREATE_PER_TEST_DIRECTORIES;

	static {
		String baseTestDir = System.getProperty("antlr-csharp-test-dir");
		boolean perTestDirectories = false;
		if (baseTestDir == null || baseTestDir.isEmpty()) {
			baseTestDir = System.getProperty("java.io.tmpdir");
			perTestDirectories = true;
		}

		if (!new File(baseTestDir).isDirectory()) {
			throw new UnsupportedOperationException("The specified base test directory does not exist: " + baseTestDir);
		}

		BASE_TEST_DIR = baseTestDir;
		CREATE_PER_TEST_DIRECTORIES = perTestDirectories;
	}

	public String tmpdir = null;

	/**
	 * If error during parser execution, store stderr here; can't return
	 * stdout and stderr.  This doesn't trap errors from running antlr.
	 */
	protected String stderrDuringParse;

	/**
	 * Errors found while running antlr
	 */
	protected StringBuilder antlrToolErrors;

	@org.junit.Rule
	public final TestRule testWatcher = new TestWatcher() {

		@Override
		protected void succeeded(Description description) {
			// remove tmpdir if no error.
			eraseTempDir();
		}

	};

	@Override
	public void testSetUp() throws Exception {
		if (CREATE_PER_TEST_DIRECTORIES) {
			// new output dir for each test
			String testDirectory = getClass().getSimpleName() + "-" + Thread.currentThread().getName() + "-" + System.currentTimeMillis();
			tmpdir = new File(BASE_TEST_DIR, testDirectory).getAbsolutePath();
		} else {
			tmpdir = new File(BASE_TEST_DIR).getAbsolutePath();
			if (!PRESERVE_TEST_DIR && new File(tmpdir).exists()) {
				eraseDirectory(new File(tmpdir));
			}
		}
		antlrToolErrors = new StringBuilder();
	}

	@Override
	public void testTearDown() throws Exception {
	}

	@Override
	public void beforeTest(RuntimeTestDescriptor descriptor) {
		System.out.println(descriptor.getTestName());
	}

	@Override
	public void afterTest(RuntimeTestDescriptor descriptor) {
	}

	@Override
	public String getTmpDir() {
		return tmpdir;
	}

	@Override
	public String getStdout() {
		return null;
	}

	@Override
	public String getParseErrors() {
		return stderrDuringParse;
	}

	@Override
	public String getANTLRToolErrors() {
		if (antlrToolErrors.length() == 0) {
			return null;
		}
		return antlrToolErrors.toString();
	}

	protected String execLexer(String grammarFileName,
							   String grammarStr,
							   String lexerName,
							   String input) {
		return execLexer(grammarFileName, grammarStr, lexerName, input, false);
	}

	@Override
	public String execLexer(String grammarFileName,
							String grammarStr,
							String lexerName,
							String input,
							boolean showDFA) {
		boolean success = rawGenerateRecognizer(grammarFileName,
				grammarStr,
				null,
				lexerName);
		assertTrue(success);
		writeFile(tmpdir, "input", input);
		writeLexerTestFile(lexerName, showDFA);
		addSourceFiles("Test.cs");
		if (!compile()) {
			System.err.println("Failed to compile!");
			return stderrDuringParse;
		}
		String output = execTest();
		if (output != null && output.length() == 0) {
			output = null;
		}
		return output;
	}

	Set<String> sourceFiles = new HashSet<>();

	private void addSourceFiles(String... files) {
		for (String file : files)
			this.sourceFiles.add(file);
	}

	@Override
	public String execParser(String grammarFileName,
							 String grammarStr,
							 String parserName,
							 String lexerName,
							 String listenerName,
							 String visitorName,
							 String startRuleName,
							 String input,
							 boolean showDiagnosticErrors) {
		boolean success = rawGenerateRecognizer(grammarFileName,
				grammarStr,
				parserName,
				lexerName,
				"-visitor");
		assertTrue(success);
		writeFile(tmpdir, "input", input);
		return rawExecRecognizer(parserName,
				lexerName,
				startRuleName,
				showDiagnosticErrors);
	}

	/**
	 * Return true if all is well
	 */
	protected boolean rawGenerateRecognizer(String grammarFileName,
											String grammarStr,
											String parserName,
											String lexerName,
											String... extraOptions) {
		return rawGenerateRecognizer(grammarFileName, grammarStr, parserName, lexerName, false, extraOptions);
	}

	/**
	 * Return true if all is well
	 */
	protected boolean rawGenerateRecognizer(String grammarFileName,
											String grammarStr,
											String parserName,
											String lexerName,
											boolean defaultListener,
											String... extraOptions) {
		ErrorQueue equeue = antlrOnString(getTmpDir(), "CSharp", grammarFileName, grammarStr, defaultListener, extraOptions);
		if (!equeue.errors.isEmpty()) {
			return false;
		}

		List<String> files = new ArrayList<String>();
		if (lexerName != null) {
			files.add(lexerName + ".cs");
		}
		if (parserName != null) {
			files.add(parserName + ".cs");
			Set<String> optionsSet = new HashSet<String>(Arrays.asList(extraOptions));
			String grammarName = grammarFileName.substring(0, grammarFileName.lastIndexOf('.'));
			if (!optionsSet.contains("-no-listener")) {
				files.add(grammarName + "Listener.cs");
				files.add(grammarName + "BaseListener.cs");
			}
			if (optionsSet.contains("-visitor")) {
				files.add(grammarName + "Visitor.cs");
				files.add(grammarName + "BaseVisitor.cs");
			}
		}
		addSourceFiles(files.toArray(new String[files.size()]));
		return true;
	}

	protected String rawExecRecognizer(String parserName,
									   String lexerName,
									   String parserStartRuleName,
									   boolean debug) {
		this.stderrDuringParse = null;
		if (parserName == null) {
			writeLexerTestFile(lexerName, false);
		} else {
			writeParserTestFile(parserName,
					lexerName,
					parserStartRuleName,
					debug);
		}

		addSourceFiles("Test.cs");
		return execRecognizer();
	}

	public String execRecognizer() {
		boolean success = compile();
		assertTrue(success);

		String output = execTest();
		if (output != null && output.length() == 0) {
			output = null;
		}
		return output;
	}

	public boolean compile() {
		try {
			return buildProject();
		} catch (Exception e) {
			e.printStackTrace(System.err);
			return false;
		}
	}

	private String locateExec() {
		return new File(tmpdir, "bin/Release/netcoreapp3.1/Test.dll").getAbsolutePath();
	}

	public boolean buildProject() {
		try {
			// save auxiliary files
			String pack = BaseCSharpTest.class.getPackage().getName().replace(".", "/") + "/";
			saveResourceAsFile(pack + "Antlr4.Test.csproj", new File(tmpdir, "Antlr4.Test.csproj"));

			// find runtime package
			final ClassLoader loader = Thread.currentThread().getContextClassLoader();
			final URL runtimeProj = loader.getResource("CSharp/Antlr4.csproj");
			if (runtimeProj == null) {
				throw new RuntimeException("C# runtime project file not found!");
			}
			File runtimeProjFile = new File(runtimeProj.getFile());
			String runtimeProjPath = runtimeProjFile.getPath();

			// add Runtime project reference
			String[] args = new String[]{
					"dotnet",
					"add",
					"Antlr4.Test.csproj",
					"reference",
					runtimeProjPath
			};
			boolean success = runProcess(args, tmpdir);
			assertTrue(success);

			// build test
			args = new String[]{
					"dotnet",
					"build",
					"Antlr4.Test.csproj",
					"-c",
					"Release"
			};
			success = runProcess(args, tmpdir);
			assertTrue(success);
		} catch (Exception e) {
			e.printStackTrace(System.err);
			return false;
		}

		return true;
	}

	private boolean runProcess(String[] args, String path) throws Exception {
		return runProcess(args, path, 0);
	}

	private boolean runProcess(String[] args, String path, int retries) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(args);
		pb.directory(new File(path));
		Process process = pb.start();
		StreamVacuum stdoutVacuum = new StreamVacuum(process.getInputStream());
		StreamVacuum stderrVacuum = new StreamVacuum(process.getErrorStream());
		stdoutVacuum.start();
		stderrVacuum.start();
		process.waitFor();
		stdoutVacuum.join();
		stderrVacuum.join();
		int exitValue = process.exitValue();
		boolean success = (exitValue == 0);
		if (!success) {
			this.stderrDuringParse = stderrVacuum.toString();
			System.err.println("runProcess command: " + Utils.join(args, " "));
			System.err.println("runProcess exitValue: " + exitValue);
			System.err.println("runProcess stdoutVacuum: " + stdoutVacuum.toString());
			System.err.println("runProcess stderrVacuum: " + stderrDuringParse);
		}
		if (exitValue == 132) {
			// Retry after SIGILL.  We are seeing this intermittently on
			// macOS (issue #2078).
			if (retries < 3) {
				System.err.println("runProcess retrying; " + retries +
						" retries so far");
				return runProcess(args, path, retries + 1);
			} else {
				System.err.println("runProcess giving up after " + retries +
						" retries");
				return false;
			}
		}
		return success;
	}

	private void saveResourceAsFile(String resourceName, File file) throws IOException {
		InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
		if (input == null) {
			System.err.println("Can't find " + resourceName + " as resource");
			throw new IOException("Missing resource:" + resourceName);
		}
		OutputStream output = new FileOutputStream(file.getAbsolutePath());
		while (input.available() > 0) {
			output.write(input.read());
		}
		output.close();
		input.close();
	}

	public String execTest() {
		String exec = locateExec();
		try {
			File tmpdirFile = new File(tmpdir);
			Path output = tmpdirFile.toPath().resolve("output");
			Path errorOutput = tmpdirFile.toPath().resolve("error-output");
			String[] args = getExecTestArgs(exec, output, errorOutput);
			ProcessBuilder pb = new ProcessBuilder(args);
			pb.directory(tmpdirFile);
			Process process = pb.start();
			StreamVacuum stdoutVacuum = new StreamVacuum(process.getInputStream());
			StreamVacuum stderrVacuum = new StreamVacuum(process.getErrorStream());
			stdoutVacuum.start();
			stderrVacuum.start();
			process.waitFor();
			stdoutVacuum.join();
			stderrVacuum.join();
			String writtenOutput = TestOutputReading.read(output);
			this.stderrDuringParse = TestOutputReading.read(errorOutput);
			int exitValue = process.exitValue();
			String stdoutString = stdoutVacuum.toString().trim();
			String stderrString = stderrVacuum.toString().trim();
			if (exitValue != 0) {
				System.err.println("execTest command: " + Utils.join(args, " "));
				System.err.println("execTest exitValue: " + exitValue);
			}
			if (!stdoutString.isEmpty()) {
				System.err.println("execTest stdoutVacuum: " + stdoutString);
			}
			if (!stderrString.isEmpty()) {
				System.err.println("execTest stderrVacuum: " + stderrString);
			}
			return writtenOutput;
		} catch (Exception e) {
			System.err.println("can't exec recognizer");
			e.printStackTrace(System.err);
		}
		return null;
	}

	private String[] getExecTestArgs(String exec, Path output, Path errorOutput) {
		return new String[]{
				"dotnet", exec, new File(tmpdir, "input").getAbsolutePath(),
				output.toAbsolutePath().toString(),
				errorOutput.toAbsolutePath().toString()
		};
	}

	protected void writeParserTestFile(String parserName,
									   String lexerName,
									   String parserStartRuleName,
									   boolean debug) {
		ST outputFileST = new ST(
				"using System;\n" +
						"using Antlr4.Runtime;\n" +
						"using Antlr4.Runtime.Tree;\n" +
						"using System.IO;\n" +
						"using System.Text;\n" +
						"\n" +
						"public class Test {\n" +
						"    public static void Main(string[] args) {\n" +
						"        var input = CharStreams.fromPath(args[0]);\n" +
						"        using (FileStream fsOut = new FileStream(args[1], FileMode.Create, FileAccess.Write))\n" +
						"        using (FileStream fsErr = new FileStream(args[2], FileMode.Create, FileAccess.Write))\n" +
						"        using (TextWriter output = new StreamWriter(fsOut),\n" +
						"                          errorOutput = new StreamWriter(fsErr)) {\n" +
						"                <lexerName> lex = new <lexerName>(input, output, errorOutput);\n" +
						"                CommonTokenStream tokens = new CommonTokenStream(lex);\n" +
						"                <createParser>\n" +
						"			 parser.BuildParseTree = true;\n" +
						"                ParserRuleContext tree = parser.<parserStartRuleName>();\n" +
						"                ParseTreeWalker.Default.Walk(new TreeShapeListener(), tree);\n" +
						"        }\n" +
						"    }\n" +
						"}\n" +
						"\n" +
						"class TreeShapeListener : IParseTreeListener {\n" +
						"	public void VisitTerminal(ITerminalNode node) { }\n" +
						"	public void VisitErrorNode(IErrorNode node) { }\n" +
						"	public void ExitEveryRule(ParserRuleContext ctx) { }\n" +
						"\n" +
						"	public void EnterEveryRule(ParserRuleContext ctx) {\n" +
						"		for (int i = 0; i \\< ctx.ChildCount; i++) {\n" +
						"			IParseTree parent = ctx.GetChild(i).Parent;\n" +
						"			if (!(parent is IRuleNode) || ((IRuleNode)parent).RuleContext != ctx) {\n" +
						"				throw new Exception(\"Invalid parse tree shape detected.\");\n" +
						"			}\n" +
						"		}\n" +
						"	}\n" +
						"}"
		);
		ST createParserST = new ST("        <parserName> parser = new <parserName>(tokens, output, errorOutput);\n");
		if (debug) {
			createParserST =
					new ST(
							"        <parserName> parser = new <parserName>(tokens, output, errorOutput);\n" +
									"        parser.AddErrorListener(new DiagnosticErrorListener());\n");
		}
		outputFileST.add("createParser", createParserST);
		outputFileST.add("parserName", parserName);
		outputFileST.add("lexerName", lexerName);
		outputFileST.add("parserStartRuleName", parserStartRuleName);
		writeFile(tmpdir, "Test.cs", outputFileST.render());
	}

	protected void writeLexerTestFile(String lexerName, boolean showDFA) {
		ST outputFileST = new ST(
				"using System;\n" +
						"using Antlr4.Runtime;\n" +
						"using System.IO;\n" +
						"using System.Text;\n" +
						"\n" +
						"public class Test {\n" +
						"    public static void Main(string[] args) {\n" +
						"        var input = CharStreams.fromPath(args[0]);\n" +
						"        using (FileStream fsOut = new FileStream(args[1], FileMode.Create, FileAccess.Write))\n" +
						"        using (FileStream fsErr = new FileStream(args[2], FileMode.Create, FileAccess.Write))\n" +
						"        using (TextWriter output = new StreamWriter(fsOut),\n" +
						"                          errorOutput = new StreamWriter(fsErr)) {\n" +
						"        <lexerName> lex = new <lexerName>(input, output, errorOutput);\n" +
						"        CommonTokenStream tokens = new CommonTokenStream(lex);\n" +
						"        tokens.Fill();\n" +
						"        foreach (object t in tokens.GetTokens())\n" +
						"			output.WriteLine(t);\n" +
						(showDFA ? "        output.Write(lex.Interpreter.GetDFA(Lexer.DEFAULT_MODE).ToLexerString());\n" : "") +
						"    }\n" +
						"}\n" +
						"}"
		);

		outputFileST.add("lexerName", lexerName);
		writeFile(tmpdir, "Test.cs", outputFileST.render());
	}

	protected void eraseDirectory(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					eraseDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		dir.delete();
	}

	@Override
	public void eraseTempDir() {
		if (shouldEraseTempDir()) {
			File tmpdirF = new File(tmpdir);
			if (tmpdirF.exists()) {
				eraseDirectory(tmpdirF);
				tmpdirF.delete();
			}
		}
	}

	private boolean shouldEraseTempDir() {
		return tmpdir!=null && !PRESERVE_TEST_DIR;
	}

	/**
	 * Return map sorted by key
	 */
	public <K extends Comparable<? super K>, V> LinkedHashMap<K, V> sort(Map<K, V> data) {
		LinkedHashMap<K, V> dup = new LinkedHashMap<K, V>();
		List<K> keys = new ArrayList<K>();
		keys.addAll(data.keySet());
		Collections.sort(keys);
		for (K k : keys) {
			dup.put(k, data.get(k));
		}
		return dup;
	}

	protected static void assertEquals(String msg, int a, int b) {
		org.junit.Assert.assertEquals(msg, a, b);
	}

	protected static void assertEquals(String a, String b) {
		org.junit.Assert.assertEquals(a, b);
	}
}