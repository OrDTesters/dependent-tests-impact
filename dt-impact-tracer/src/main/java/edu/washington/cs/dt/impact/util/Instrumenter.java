/**
 * Copyright 2014 University of Washington. All Rights Reserved.
 * @author Wing Lam
 * 
 * Tool used to instrument class and test files.
 */

package edu.washington.cs.dt.impact.util;

import edu.washington.cs.dt.impact.util.Constants.TECHNIQUE;
import soot.Body;
import soot.BodyTransformer;
import soot.Local;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.jimple.IdentityStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.RetStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.tagkit.AnnotationTag;
import soot.tagkit.LineNumberTag;
import soot.tagkit.VisibilityAnnotationTag;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.util.Chain;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public class Instrumenter extends BodyTransformer{

    private static SootClass tracerClass;
    private static SootMethod trace, output, reset, selectionOutput, selectionTrace;
    private static final String JUNIT4_TAG = "VisibilityAnnotationTag";
    private static final String JUNIT4_TYPE = "Lorg/junit/Test;";
    private static final String JUNIT4_BEFORE = "Lorg/junit/Before;";
    private static final String JUNIT4_AFTER = "Lorg/junit/After;";
    private static final String JUNIT3_CLASS = "junit.framework.TestCase";
    private static final String JUNIT3_RETURN = "void";
    private static final String JUNIT3_METHOD_PREFIX = "test";
    private static TECHNIQUE technique = Constants.DEFAULT_TECHNIQUE;

    public Instrumenter() {
	Scene.v().setSootClassPath(System.getProperty("java.class.path"));

        tracerClass = Scene.v().loadClassAndSupport("edu.washington.cs.dt.impact.util.Tracer");
        trace = tracerClass.getMethodByName("trace");
        selectionTrace = tracerClass.getMethodByName("selectionTrace");
        selectionOutput = tracerClass.getMethodByName("selectionOutput");
        output = tracerClass.getMethodByName("output");
        reset = tracerClass.getMethodByName("reset");
    }

    public Instrumenter(TECHNIQUE t) {
        this();
        technique = t;
    }

    /* internalTransform goes through a method body and inserts
     * counter instructions before an INVOKESTATIC instruction
     */
    @Override
    protected void internalTransform(Body body, String phase,
            @SuppressWarnings("rawtypes") Map options) {
        SootMethod method = body.getMethod();

        if (method.getName().equals("<init>") || method.getName().equals("<clinit>")) {
            return;
        }

        boolean isSetupOrTeardown = false;

        boolean isJUnit4 = false;
        VisibilityAnnotationTag vat = (VisibilityAnnotationTag) method.getTag(JUNIT4_TAG);
        if (vat != null) {
            List<AnnotationTag> tags = vat.getAnnotations();
            for (AnnotationTag at : tags) {
                if (!isJUnit4) {
                    isJUnit4 = at.getType().equals(JUNIT4_TYPE);
                }
                if (!isSetupOrTeardown) {
                    isSetupOrTeardown = at.getType().equals(JUNIT4_BEFORE)
                            || at.getType().equals(JUNIT4_AFTER);
                }
            }
        }

        boolean isJUnit3 = false;
        boolean extendsJUnit = false;
        if (!isJUnit4 && method.getName().length() > 3) {
            String retType = method.getReturnType().toString();
            String prefix = method.getName().substring(0, 4);
            SootClass superClass = method.getDeclaringClass().getSuperclass();
            while(superClass.hasSuperclass()) {
                if (superClass.getName().equals(JUNIT3_CLASS)) {
                    extendsJUnit = true;
                    break;
                }
                superClass = superClass.getSuperclass();
            }
            isJUnit3 = method.isPublic() && retType.equals(JUNIT3_RETURN)
                    && extendsJUnit && prefix.equals(JUNIT3_METHOD_PREFIX);
        }

        // exclude the instrumentation of setups/teardowns
        // and JUnit3 methods that are not tests
        if ((extendsJUnit && !isJUnit3) || isSetupOrTeardown) {
            return;
        }

        Chain<Unit> units = body.getUnits();
        // get a snapshot iterator of the unit since we are going to
        // mutate the chain when iterating over it.
        Iterator<Unit> stmtIt = units.snapshotIterator();

        String packageMethodName = method.getDeclaringClass().getName() + "." + method.getName();
        if (isJUnit4 || isJUnit3) {
            // instrumentation of JUnit files

            // get access to Throwable class and toString method
            SootClass thrwCls = Scene.v().getSootClass("java.lang.Throwable");
            List<Stmt> probe = new ArrayList<Stmt>();
            PatchingChain<Unit> pchain = body.getUnits();

            Stmt sFirstNonId = getFirstNonIdStmt(pchain);
            Stmt sLast = (Stmt) pchain.getLast();

            // Don't instrument empty methods
            if (sFirstNonId == sLast) {
                return;
            }

            // FOR NOW, no other returns allowed apart from last stmt
            for (Unit u : pchain) {
                assert (!(u instanceof ReturnStmt) && !(u instanceof RetStmt)) || u == sLast;
            }

            Stmt sGotoLast = Jimple.v().newGotoStmt(sLast);
            probe.add(sGotoLast);
            Local lException1 = getCreateLocal(body, "<ex1>", RefType.v(thrwCls));
            Stmt sCatch = Jimple.v().newIdentityStmt(lException1, Jimple.v().newCaughtExceptionRef());
            probe.add(sCatch);

            // TODO after catching an exception in a test, throw the exception back
            // Type throwType = thrwCls.getType();
            // Local lSysOut = getCreateLocal(body, "<throw>", throwType);
            // Stmt callThrow = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(lException1, initThrow.makeRef(),
            //         lSysOut));
            // probe.add(callThrow);

            insertRightBeforeNoRedirect(pchain, probe, sLast);

            // insert trap (catch)
            body.getTraps().add(Jimple.v().newTrap(thrwCls, sFirstNonId, sGotoLast, sCatch));

            // Do not forget to insert instructions to report the counter
            stmtIt = units.snapshotIterator();
            while (stmtIt.hasNext()) {
                Stmt stmt = (Stmt)stmtIt.next();

                if ((stmt instanceof ReturnStmt)
                        ||(stmt instanceof ReturnVoidStmt)) {

                    if (technique != TECHNIQUE.SELECTION) {
                        // output the contents of the tracer
                        InvokeExpr reportExpr= Jimple.v().newStaticInvokeExpr(output.makeRef(),
                                StringConstant.v(packageMethodName));
                        Stmt reportStmt = Jimple.v().newInvokeStmt(reportExpr);
                        units.insertBefore(reportStmt, stmt);

                        // reset the tracer
                        InvokeExpr resetExpr= Jimple.v().newStaticInvokeExpr(reset.makeRef());
                        Stmt resetStmt = Jimple.v().newInvokeStmt(resetExpr);
                        units.insertAfter(resetStmt, reportStmt);
                    } else {
                        // output the contents of the tracer
                        InvokeExpr reportExpr= Jimple.v().newStaticInvokeExpr(
                                selectionOutput.makeRef(), StringConstant.v(packageMethodName));
                        Stmt reportStmt = Jimple.v().newInvokeStmt(reportExpr);
                        units.insertBefore(reportStmt, stmt);

                        // reset the tracer
                        InvokeExpr resetExpr= Jimple.v().newStaticInvokeExpr(reset.makeRef());
                        Stmt resetStmt = Jimple.v().newInvokeStmt(resetExpr);
                        units.insertAfter(resetStmt, reportStmt);
                    }
                }
            }
        } else {
            if (technique == TECHNIQUE.SELECTION) {
                // instrumentation of class files for test selection
                // creates selectionOutput directory containing what statements
                // each test executed without duplicates
                Set<Unit> duplicates = new HashSet<Unit>();
                StringBuffer sb = new StringBuffer();
                UnitGraph ug = new ExceptionalUnitGraph(body);
                Stack<Unit> remaining = new Stack<Unit>();
                remaining.addAll(ug.getHeads());

                List<Stmt> incStmts = new LinkedList<Stmt>();
                List<Stmt> stmts = new LinkedList<Stmt>();

                while(!remaining.empty()) {
                    Unit current = remaining.pop();
                    // cast back to a statement.
                    Stmt stmt = (Stmt)current;

                    if (stmt instanceof ReturnVoidStmt) {
                        continue;
                    }

                    if (!duplicates.contains(current)) {
                        List<Unit> children = ug.getSuccsOf(current);
                        children.sort((c1, c2) -> {
                            final int compareLine = Integer.compare(c1.getJavaSourceStartLineNumber(), c2.getJavaSourceStartLineNumber());
                            final int compareColumn = Integer.compare(c1.getJavaSourceStartColumnNumber(), c2.getJavaSourceStartColumnNumber());

                            return compareLine != 0 ? compareLine : compareColumn;
                        });
                        // don't check identity statements (parameters)
                        if (!(stmt instanceof IdentityStmt)) {
                            for (Unit u : children) {
                                sb.append(packageMethodName + " : "
                                        + current.toString().split(" goto")[0] + " >>>>>>>> "
                                        + packageMethodName + " : "
                                        + u.toString().split(" goto")[0] + "\n");
                            }
                            InvokeExpr incExpr= Jimple.v().newStaticInvokeExpr(
                                    selectionTrace.makeRef(), StringConstant.v(stmt.toString()),
                                    StringConstant.v(packageMethodName));
                            Stmt incStmt = Jimple.v().newInvokeStmt(incExpr);
                            incStmts.add(incStmt);
                            stmts.add(stmt);
                        }
                        remaining.addAll(children);
                        duplicates.add(current);
                    }
                }

                for (int i = 0; i < incStmts.size(); i++) {
                    units.insertBefore(incStmts.get(i), stmts.get(i));
                }

                selectionOutput(packageMethodName, sb);
            } else {
                // instrumentation of class files for test prioritization and parallelization
                Set<Integer> lines = new HashSet<Integer>();
                while (stmtIt.hasNext()) {
                    // cast back to a statement.
                    Stmt stmt = (Stmt)stmtIt.next();

                    if (stmt instanceof ReturnVoidStmt) {
                        continue;
                    }

                    if (stmt instanceof IdentityStmt) {
                        continue;
                    }

                    LineNumberTag t = (LineNumberTag) stmt.getTag("LineNumberTag");
                    if (stmt.hasTag("LineNumberTag") && !lines.contains(t.getLineNumber())) {
                        lines.add(t.getLineNumber());

                        InvokeExpr incExpr= Jimple.v().newStaticInvokeExpr(trace.makeRef(),
                                StringConstant.v(stmt.toString()),
                                StringConstant.v(packageMethodName));
                        Stmt incStmt = Jimple.v().newInvokeStmt(incExpr);
                        units.insertBefore(incStmt, stmt);
                    }
                }
            }
        }
    }

    // used for the instrumentation of test selection class files
    private static void selectionOutput(String packageMethodName, StringBuffer sb) {
        File theDir = new File("selectionOutput");
        // if the directory does not exist, create it
        if (!theDir.exists()) {
            try {
                Files.createDirectory(theDir.toPath());
            } catch (FileAlreadyExistsException ignored) {
                // The directory must have been created in between the check above and our attempt to create it.
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        FileWriter output = null;
        BufferedWriter writer = null;
        try {
            output = new FileWriter("selectionOutput" + File.separator + packageMethodName);
            writer = new BufferedWriter(output);
            writer.write(sb.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
                if (output != null) {
                    output.close();
                }
            } catch (IOException e) {
                // Ignore issues during closing
            }
        }
    }

    private void insertRightBeforeNoRedirect(PatchingChain<Unit> pchain,
            List<Stmt> instrumCode, Stmt s) {
        assert !(s instanceof IdentityStmt);
        for (Object stmt : instrumCode) {
            pchain.insertBeforeNoRedirect((Unit) stmt, s);
        }
    }

    private static Stmt getFirstNonIdStmt(PatchingChain<Unit> pchain) {
        Stmt sFirstNonId = null;
        for (Iterator<Unit> it = pchain.iterator(); it.hasNext(); ) {
            sFirstNonId = (Stmt) it.next();
            if (!(sFirstNonId instanceof IdentityStmt)) {
                break;
            }
        }
        return sFirstNonId;
    }

    private static Local getCreateLocal(Body b, String localName, Type t) {
        // try getting it
        Local l = getLocal(b, localName);
        if (l != null) {
            assert l.getType().equals(t); // ensure type is correct
            return l;
        }
        // no luck; create it
        Chain<Local> locals = b.getLocals();
        l = Jimple.v().newLocal(localName, t);
        locals.add(l);
        return l;
    }

    private static Local getLocal(Body b, String localName) {
        // look for existing bs local, and return it if found
        Chain<Local> locals = b.getLocals();
        for (Iterator<Local> itLoc = locals.iterator(); itLoc.hasNext(); ) {
            Local l = itLoc.next();
            if (l.getName().equals(localName)) {
                return l;
            }
        }
        return null;
    }
}
