package boa.compiler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.stringtemplate.v4.ST;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;

import org.scannotation.ClasspathUrlFinder;

import boa.compiler.ast.Program;
import boa.compiler.ast.Start;
import boa.compiler.transforms.LocalAggregationTransformer;
import boa.compiler.transforms.VisitorMergingTransformer;
import boa.compiler.transforms.VisitorOptimizingTransformer;
import boa.compiler.visitors.AbstractCodeGeneratingVisitor;
import boa.compiler.visitors.CodeGeneratingVisitor;
import boa.compiler.visitors.TaskClassifyingVisitor;
import boa.compiler.visitors.TypeCheckingVisitor;
import boa.parser.ParseException;
import boa.parser.BoaParser;

/**
 * The main entry point for the Boa compiler.
 *
 * @author anthonyu
 * @author rdyer
 */
public class BoaCompiler {
	private static Logger LOG = Logger.getLogger(BoaCompiler.class);

	public static void main(final String[] args) throws IOException, ParseException {
		// parse the command line options
		final Options options = new Options();
		options.addOption("l", "libs", true, "extra jars (functions/aggregators) to be compiled in");
		options.addOption("i", "in", true, "file(s) to be compiled (comma-separated list)");
		options.addOption("o", "out", true, "the name of the resulting jar");
		options.addOption("nv", "no-visitor-fusion", false, "disable visitor fusion");
		options.addOption("v", "visitors-fused", true, "number of visitors to fuse");
		options.addOption("n", "name", true, "the name of the generated main class");

		final CommandLine cl;
		try {
			cl = new PosixParser().parse(options, args);
		} catch (final org.apache.commons.cli.ParseException e) {
			System.err.println(e.getMessage());
			new HelpFormatter().printHelp("BoaCompiler", options);

			return;
		}

		// get the filename of the program we will be compiling
		final ArrayList<File> inputFiles = new ArrayList<File>();
		if (cl.hasOption('i')) {
			final String[] inputPaths = cl.getOptionValue('i').split(",");

			for (final String s : inputPaths) {
				final File f = new File(s);
				if (!f.exists())
					System.err.println("File '" + s + "' does not exist, skipping");
				else
					inputFiles.add(new File(s));
			}
		}

		if (inputFiles.size() == 0) {
			System.err.println("missing required option `in'");
			new HelpFormatter().printHelp("BoaCompiler", options);

			return;
		}


		// get the name of the generated class
		final String className;
		if (cl.hasOption('n')) {
			className = cl.getOptionValue('n');
		} else {
			String s = "";
			for (final File f : inputFiles) {
				if (s.length() != 0)
					s += "_";
				if (f.getName().indexOf('.') != -1)
					s += pascalCase(f.getName().substring(0, f.getName().lastIndexOf('.')));
				else
					s += pascalCase(f.getName());
			}
			className = s;
		}

		// get the filename of the jar we will be writing
		final String jarName;
		if (cl.hasOption('o'))
			jarName = cl.getOptionValue('o');
		else
			jarName = className + ".jar";

		// make the output directory
		final File outputRoot = new File(new File(System.getProperty("java.io.tmpdir")), UUID.randomUUID().toString());
		final File outputSrcDir = new File(outputRoot, "boa");
		if (!outputSrcDir.mkdirs())
			throw new IOException("unable to mkdir " + outputSrcDir);

		// find custom libs to load
		final List<URL> libs = new ArrayList<URL>();
		if (cl.hasOption('l'))
			for (final String lib : cl.getOptionValues('l'))
				libs.add(new File(lib).toURI().toURL());

		final File outputFile = new File(outputSrcDir, className + ".java");
		final BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(outputFile));
		try {
			final List<String> jobnames = new ArrayList<String>();
			final List<String> jobs = new ArrayList<String>();
			BoaParser parser = null;
			boolean isSimple = true;

			final List<Program> visitorPrograms = new ArrayList<Program>();

			SymbolTable.initialize(libs);

			for (int i = 0; i < inputFiles.size(); i++) {
				final File f = inputFiles.get(i);
				final BufferedReader r = new BufferedReader(new FileReader(f));

				try {
					if (parser == null)
						parser = new BoaParser(r);
					else
						BoaParser.ReInit(r);
					parser.setTabSize(4);
					final Start p = BoaParser.Start();

					final String jobName = "" + i;

					final TypeCheckingVisitor typeChecker = new TypeCheckingVisitor();
					typeChecker.start(p, new SymbolTable());

					final TaskClassifyingVisitor simpleVisitor = new TaskClassifyingVisitor();
					simpleVisitor.start(p);

					LOG.info(f.getName() + ": task complexity: " + (!simpleVisitor.isComplex() ? "simple" : "complex"));
					isSimple &= !simpleVisitor.isComplex();

					new LocalAggregationTransformer().start(p);
						
					// if a job has no visitor, let it have its own method
					// also let jobs have own methods if visitor merging is disabled
					if (!simpleVisitor.isComplex() || cl.hasOption("nv") || inputFiles.size() == 1) {
						if (!simpleVisitor.isComplex())
							new VisitorOptimizingTransformer().start(p);

						final CodeGeneratingVisitor cg = new CodeGeneratingVisitor(jobName);
						cg.start(p);
						jobs.add(cg.getCode());

						jobnames.add(jobName);
					}
					// if a job has visitors, fuse them all together into a single program
					else {
						p.getProgram().jobName = jobName;
						visitorPrograms.add(p.getProgram());
					}
				} catch (final Exception e) {
					System.err.print(f.getName() + ": compilation failed: ");
					e.printStackTrace();
				} finally {
					r.close();
				}
			}

			final int maxVisitors;
			if (cl.hasOption('v'))
				maxVisitors = Integer.parseInt(cl.getOptionValue('v'));
			else
				maxVisitors = Integer.MAX_VALUE;

			if (!visitorPrograms.isEmpty())
				try {
					for (final Program p : new VisitorMergingTransformer().mergePrograms(visitorPrograms, maxVisitors)) {
						new VisitorOptimizingTransformer().start(p);

						final CodeGeneratingVisitor cg = new CodeGeneratingVisitor(p.jobName);
						cg.start(p);
						jobs.add(cg.getCode());
		
						jobnames.add(p.jobName);
					}
				} catch (final Exception e) {
					System.err.println("error fusing visitors - falling back: " + e);
					e.printStackTrace();

					for (final Program p : visitorPrograms) {
						new VisitorOptimizingTransformer().start(p);

						final CodeGeneratingVisitor cg = new CodeGeneratingVisitor(p.jobName);
						cg.start(p);
						jobs.add(cg.getCode());

						jobnames.add(p.jobName);
					}
				}

			if (jobs.size() == 0)
				throw new RuntimeException("no files compiled without error");

			final ST st = AbstractCodeGeneratingVisitor.stg.getInstanceOf("Program");

			st.add("name", className);
			st.add("numreducers", inputFiles.size());
			st.add("jobs", jobs);
			st.add("jobnames", jobnames);
			st.add("combineTables", CodeGeneratingVisitor.combineTableStrings);
			st.add("reduceTables", CodeGeneratingVisitor.reduceTableStrings);
			st.add("splitsize", isSimple ? 64 * 1024 * 1024 : 10 * 1024 * 1024);

			o.write(st.render().getBytes());
		} finally {
			o.close();
		}

		// compile the generated .java file
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		LOG.info("compiling: " + outputFile);
		LOG.info("classpath: " + System.getProperty("java.class.path"));
		if (compiler.run(null, null, null, "-cp", System.getProperty("java.class.path"), outputFile.toString()) != 0)
			throw new RuntimeException("compile failed");

		// find the location of the jar this class is in
		final String path = ClasspathUrlFinder.findClassBase(BoaCompiler.class).getPath();
		// find the location of the compiler distribution
		final File root = new File(path.substring(path.indexOf(':') + 1, path.indexOf('!'))).getParentFile();

		final List<File> libJars = new ArrayList<File>();
		libJars.add(new File(root, "boa-runtime.jar"));
		if (cl.hasOption('l'))
			for (final String s : Arrays.asList(cl.getOptionValues('l')))
				libJars.add(new File(s));

		generateJar(jarName, outputRoot, libJars);

		delete(outputRoot);
	}

	private static final void delete(final File f) throws IOException {
		if (f.isDirectory())
			for (final File g : f.listFiles())
				delete(g);

		if (!f.delete())
			throw new IOException("unable to delete file " + f);
	}

	private static void generateJar(final String jarName, final File dir, final List<File> libJars) throws IOException, FileNotFoundException {
		final int offset = dir.toString().length() + 1;

		final JarOutputStream jar = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(new File(jarName))));
		try {
			for (final File f : findFiles(dir, new ArrayList<File>()))
				putJarEntry(jar, f, f.getPath().substring(offset));

			for (final File f : libJars)
				putJarEntry(jar, f, "lib" + File.separatorChar + f.getName());
		} finally {
			jar.close();
		}
	}

	private static final List<File> findFiles(final File f, final List<File> l) {
		if (f.isDirectory())
			for (final File g : f.listFiles())
				findFiles(g, l);
		else
			l.add(f);

		return l;
	}

	private static void putJarEntry(final JarOutputStream jar, final File f, final String path) throws IOException {
		jar.putNextEntry(new ZipEntry(path));

		final InputStream in = new BufferedInputStream(new FileInputStream(f));
		try {
			final byte[] b = new byte[4096];
			int len;
			while ((len = in.read(b)) > 0)
				jar.write(b, 0, len);
		} finally {
			in.close();
		}

		jar.closeEntry();
	}

	private static String pascalCase(final String string) {
		final StringBuilder pascalized = new StringBuilder();

		boolean upper = true;
		for (final char c : string.toCharArray())
			if (Character.isDigit(c) || c == '_') {
				pascalized.append(c);
				upper = true;
			} else if (!Character.isDigit(c) && !Character.isLetter(c)) {
				upper = true;
			} else if (Character.isLetter(c)) {
				pascalized.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}

		return pascalized.toString();
	}
}
