package edu.washington.cs.dt.impact.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class FailedTestRemover {
    private static final String AUTO_REMOVED_METHODS_FILEPATH = "auto-removed-methods.txt";
    private static final String OUT_DIR = "out";

    private final List<JavaFile> javaFiles = new ArrayList<>();
    private final List<String> removedMethods = new ArrayList<>();

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java FailedTestRemover <classpath> <filenames>");
            System.out.println();

            System.out.print("Tries to compile the files specified by <filenames> using the <classpath> ");
            System.out.print("(separated by " + System.getProperty("path.separator") + "), removing methods until it compiles. ");
            System.out.print("Writes out each changed file to to out/<filename> ");
            System.out.print("after each attempted compilation/removal cycle.");
            System.out.println();

            System.out.println();
            System.out.println("Example ($DT_TOOLS should include the javaparser .jar and the .jar for the FailedTestRemover): ");
            System.out.println();
            System.out.println("    java -cp $DT_TOOLS: edu.washington.cs.dt.impact.tools.FailedTestRemover . Test.java");
            System.out.println("    java -cp $DT_TOOLS: edu.washington.cs.dt.impact.tools.FailedTestRemover $NEW_DT_LIBS:$NEW_DT_CLASS:$DT_TOOLS: RegressionTest0.java");
            System.out.println("    java -cp $DT_TOOLS: edu.washington.cs.dt.impact.tools.FailedTestRemover . Test.java Test2.java");
            return;
        }

        // Necessary because the JavaCompiler class won't expand wildcards.
        final String classPath = buildClassPath(args[0].split(System.getProperty("path.separator")));
        // Everything but the first argument must be the filenames we want to compile
        final List<String> filenames = new ArrayList<>(Arrays.asList(Arrays.copyOfRange(args, 1, args.length)));

        System.out.println("Started running at: " + new Date().toString());
        System.out.println("Trying to compile " + filenames);
//        System.out.println("Classpath is: " + classPath);
        System.out.println();

        FailedTestRemover failedTestRemover = new FailedTestRemover(filenames, classPath);
        failedTestRemover.run();
    }

    private FailedTestRemover(final List<String> filenames, final String classPath) throws IOException {
        final Path outdirPath = Paths.get(OUT_DIR);
        if (!Files.isDirectory(outdirPath)) {
            Files.deleteIfExists(outdirPath);
            Files.createDirectory(outdirPath);
        }

        for (final String filename : filenames) {
            final File file = new File(filename);
            javaFiles.add(new JavaFile(filename, classPath, outdirPath.resolve(file.getName())));
        }
    }

    /**
     * Ex: For int f(int a, int b), returns "int a, int b"
     * @return A string containing the parameters separated by commas.
     */
    private static String getParametersAsString(final MethodDeclaration method) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < method.getParameters().size(); i++) {
            final Parameter parameter = method.getParameters().get(i);

            result.append(parameter.toString());

            if (i != (method.getParameters().size() - 1)) {
                result.append(", ");
            }
        }

        return result.toString();
    }

    /**
     * @return The fully qualified name of this method: packageName.className.methodName(paramNames)
     */
    private static String createRemovedMethodString(final String packageName,
                                                    final ClassOrInterfaceDeclaration classDeclaration,
                                                    final MethodDeclaration method) {
        // Not using .getDeclarationAsString because it includes the return type, which wouldn't work with concatting below
        final String methodName = method.getName() + "(" + getParametersAsString(method) + ")";

        return packageName + classDeclaration.getName() + "." + methodName;
    }


    /**
     * Tries to repeatedly compile/fix errors for each file.
     */
    private void run() throws Exception {
        while (javaFiles.size() > 0) {
            final JavaFile javaFile = javaFiles.remove(0);
            javaFile.open();
            run(javaFile);
        }
    }

    /**
     * Tries to repeatedly compile the file until there were no errors that occurred during compilation.
     */
    private void run(final JavaFile javaFile) throws Exception {
        int errorCount = -1;
        while (errorCount != 0) {
            System.out.println("Trying to compile: " + javaFile.getFilename());
            final DiagnosticCollector<JavaFileObject> diagnostics = javaFile.tryCompile();

            errorCount = getDiagnosticCount(diagnostics, Diagnostic.Kind.ERROR);

            System.out.println("Compiling " + (errorCount == 0 ? "succeeded" : "failed")
                    + " with " + errorCount + " errors.");

            final List<MethodDeclaration> methodsWithErrors = javaFile.getMethodsWithErrors(diagnostics);

            if (methodsWithErrors.size() > 0) {
                System.out.print("Removing: ");

                for (int i = 0; i < methodsWithErrors.size(); i++) {
                    MethodDeclaration method = methodsWithErrors.get(i);

                    if (i + 1 == methodsWithErrors.size()) {
                        System.out.print(method.getName());
                    } else {
                        System.out.print(method.getName() + ", ");
                    }

                    final String removedMethod = javaFile.removeMethod(method);

                    if (removedMethod != null) {
                        removedMethods.add(removedMethod);
                    } else {
                        throw new Exception("Failed to remove " + method.getName() + "!");
                    }
                }

                System.out.println();
            }

            javaFile.writeAndReloadCompilationUnit();
        }

        writeAutoRemovedMethodList();

        System.out.println("Successfully compiled at: " + new Date().toString());
        System.out.println();
    }

    /**
     * Writes a list of tests that were removed from the file to auto-removed-methods.txt
     */
    private void writeAutoRemovedMethodList() throws IOException {
        final StringBuilder builder = new StringBuilder();

        builder.append("# This file contains the list of tests that were automatically removed to make the file compile with the new version of the subject.\n");

        if (removedMethods.size() == 0) {
            builder.append("# There were no tests that failed to compiled.");
        } else {
            for (final String methodName : removedMethods) {
                builder.append(methodName).append("\n");
            }
        }

        final FileOutputStream outputStream = new FileOutputStream(AUTO_REMOVED_METHODS_FILEPATH);
        outputStream.write(builder.toString().getBytes());
        outputStream.flush();
        outputStream.close();
    }

    private int getDiagnosticCount(final DiagnosticCollector<JavaFileObject> diagnostics,
                                   final Diagnostic.Kind kind) {
        int count = 0;

        for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == kind) {
                count++;
            }
        }

        return count;
    }

    /**
     * This function builds a classpath from the passed Strings
     * From https://stackoverflow.com/questions/22965975/how-to-set-up-classpath-of-javacompiler-to-multiple-jar-files-using-wildcard
     *
     * @param paths classpath elements
     * @return returns the complete classpath with wildcards expanded
     */
    public static String buildClassPath(String... paths) {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            if (path.endsWith("*")) {
                path = path.substring(0, path.length() - 1);
                File pathFile = new File(path);
                System.out.println(path);
                for (File file : Objects.requireNonNull(pathFile.listFiles())) {
                    if (file.isFile() && file.getName().endsWith(".jar")) {
                        sb.append(path);
                        sb.append(file.getName());
                        sb.append(System.getProperty("path.separator"));
                    }
                }
            } else {
                sb.append(path);
                sb.append(System.getProperty("path.separator"));
            }
        }
        return sb.toString();
    }

    private final class MethodRemoverVisitor extends ModifierVisitor<Void> {
        private final MethodDeclaration method;

        private boolean found = false;

        MethodRemoverVisitor(final MethodDeclaration method) {
            this.method = method;
        }

        @Override
        public Visitable visit(MethodDeclaration n, Void arg) {
            if (n.getSignature().equals(method.getSignature())) {
                this.found = true;

                return null;
            }

            return super.visit(n, arg);
        }

        boolean succeeded() {
            return found;
        }
    }

    /**
     * The basic Java file which supports compiling/removing methods.
     */
    private final class JavaFile {
        private CompilationUnit compilationUnit;
        private List<ClassOrInterfaceDeclaration> classList = new ArrayList<>();
        private final String filename;
        private final String classPath;
        private final Path outfileName;

        private JavaFile(String filename, String classPath, Path outfileName) {
            this.filename = filename;
            this.classPath = classPath;
            this.outfileName = outfileName;
        }

        private void open() throws IOException {
            final File sourceFile = new File(filename);
            compilationUnit = JavaParser.parse(sourceFile);
        }

        private String getFilename() {
            return filename;
        }

        /**
         * Finds all classes/interfaces in the file and saves them.
         * @param outfileName
         */
        private void loadClassList(final Path outfileName) throws IOException {
            compilationUnit = JavaParser.parse(outfileName.toFile());

            classList.clear();
            classList.addAll(compilationUnit.findAll(ClassOrInterfaceDeclaration.class));
        }

        private Path writeFile() throws IOException {
            Files.write(outfileName, compilationUnit.toString().getBytes());
            return outfileName;
        }

        public void writeAndReloadCompilationUnit() throws IOException {
            loadClassList(writeFile());
        }

        private MethodDeclaration findMethodAt(final long line) {
            for (final ClassOrInterfaceDeclaration classDeclaration : classList) {
                for (final BodyDeclaration bodyDeclaration : classDeclaration.getMembers()) {
                    if (bodyDeclaration instanceof MethodDeclaration) {
                        final MethodDeclaration method = (MethodDeclaration)bodyDeclaration;
                        final Position begin = method.getBegin().orElseThrow(() -> new RuntimeException("Cannot get start line for " + method.getSignature()));
                        final Position end = method.getEnd().orElseThrow(() -> new RuntimeException("Cannot get end line for " + method.getSignature()));

                        if (begin.line <= line && end.line >= line) {
                            return method;
                        }
                    }
                }
            }

            return null;
        }

        /**
         * Attempts to remove the method declaration.
         * @param method The method declaration to remove.
         * @return A String of the fully qualified name of the method removed if found, null otherwise.
         */
        private String removeMethod(final MethodDeclaration method) {
            for (final ClassOrInterfaceDeclaration classDeclaration : classList) {
                final MethodRemoverVisitor remover = new MethodRemoverVisitor(method);
                classDeclaration.accept(remover, null);

                final Optional<PackageDeclaration> packageDec = compilationUnit.getPackageDeclaration();

                if (remover.succeeded()) {
                    return createRemovedMethodString(packageDec.map(PackageDeclaration::getNameAsString).orElse(""), classDeclaration, method);
                }
            }

            return null;
        }

        /**
         * Writes the file to the output filename, then tries to compile the output file.
         */
        private DiagnosticCollector<JavaFileObject> tryCompile() throws IOException {
            loadClassList(writeFile());

            return compile();
        }

        private DiagnosticCollector<JavaFileObject> compile() throws IOException {
            final File file = outfileName.toFile();

            final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
            final List<String> compilerOptions =
                    new ArrayList<>(Arrays.asList("-classpath", classPath));

            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            final StandardJavaFileManager fileManager =
                    javaCompiler.getStandardFileManager(diagnostics, null, null);

            final Iterable<? extends JavaFileObject> fileObjects =
                    fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(file));

            final JavaCompiler.CompilationTask compilationTask =
                    javaCompiler.getTask(null, fileManager, diagnostics, compilerOptions, null, fileObjects);
            compilationTask.call();

            fileManager.close();

            return diagnostics;
        }

        private List<MethodDeclaration> getMethodsWithErrors(DiagnosticCollector<JavaFileObject> diagnostics)
                throws Exception {
            final List<MethodDeclaration> methodsWithErrors = new ArrayList<>();

            for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    final MethodDeclaration method = findMethodAt(diagnostic.getLineNumber());
                    if (method != null) {
                        if (!methodsWithErrors.contains(method)) {
                            methodsWithErrors.add(method);
                        }
                    } else {
                        throw new Exception("An error has occurred in an unknown method:\n"
                                + " Message: " + diagnostic.getMessage(Locale.getDefault()) + "\n"
                                + " Source: " + diagnostic.getSource() + "\n"
                                + " Code: " + diagnostic.getCode() + "\n"
                                + " Kind: " + diagnostic.getKind() + "\n"
                                + " Line: " + diagnostic.getLineNumber()
                                + " Position: " + diagnostic.getPosition() + "\n");
                    }
                } else {
                    System.out.println("Ignoring the following diagnostic: "
                            + diagnostic.getMessage(Locale.getDefault()));
                }
            }

            return methodsWithErrors;
        }
    }
}
