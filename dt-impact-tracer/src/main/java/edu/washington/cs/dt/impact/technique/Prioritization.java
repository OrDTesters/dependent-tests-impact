/**
 * Copyright 2014 University of Washington. All Rights Reserved.
 * @author Wing Lam
 *         Creates a list of tests that is ordered with test prioritization based on the parameters
 *         specified to the constructor.
 */

package edu.washington.cs.dt.impact.technique;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.washington.cs.dt.impact.order.Relative;
import edu.washington.cs.dt.impact.order.Standard;
import edu.washington.cs.dt.impact.util.Constants.COVERAGE;
import edu.washington.cs.dt.impact.util.Constants.ORDER;

public class Prioritization extends Test {

    /**
     *
     * @param order the order in which to order the tests with
     * @param inputTestFolder folder containing all test cases
     * @param coverage the coverage to consider when processing the test cases
     * @param dependentTestsFile the file containing the information
     *            regarding which test depends on which
     * @param getCoverage if true the coverage percentage of each test is printed
     *            if false, the execution result will be printed
     */
    public Prioritization(ORDER order, String outputFilename, File inputTestFolder, COVERAGE coverage,
            File dependentTestsFile, boolean getCoverage, File origOrder, boolean mergeDTsCoverage) {
        this(order, outputFilename, inputTestFolder, coverage, dependentTestsFile, getCoverage, origOrder,
                new Random().nextInt(), mergeDTsCoverage);
    }

    public Prioritization(ORDER order, String outputFilename, File inputTestFolder, COVERAGE coverage,
            File dependentTestsFile, boolean getCoverage, File origOrder, int seed, boolean mergeDTsCoverage) {
        super(inputTestFolder, coverage, dependentTestsFile, origOrder, mergeDTsCoverage);

        if (order == ORDER.ABSOLUTE || order == ORDER.RELATIVE) {
            Collections.sort(methodList);
            if (order == ORDER.RELATIVE) {
                methodList = new Relative(outputFilename, methodList, getCoverage, allCoverageLines).getMethodList();
            }
            orderObj = new Standard(outputFilename, methodList, getCoverage, allCoverageLines);
        } else if (order == ORDER.RANDOM) {
            Collections.shuffle(methodList, new Random(seed));
            orderObj = new Standard(outputFilename, methodList);
        } else if (order == ORDER.ORIGINAL) {
        	parseOrigOrderListToMethodList(origOrderList, getNameToMethodData(methodList));
            orderObj = new Standard(outputFilename, methodList, getCoverage, allCoverageLines);
        } else {
            System.err.println("Test prioritization is specified with an incompatible order."
                    + " Compatible orders are: absolute, relative and random.");
            System.exit(0);
        }
    }

    public Prioritization(ORDER order, String outputFilename, COVERAGE coverage, List<String> allDTList,
            boolean getCoverage, List<String> origOrder, File inputTestFolder, boolean randomizeOriginal,
                          boolean mergeDTsCoverage) {
        super(coverage, allDTList, inputTestFolder, mergeDTsCoverage);
        if (order == ORDER.ABSOLUTE || order == ORDER.RELATIVE) {
            Collections.sort(methodList);
            if (order == ORDER.RELATIVE) {
                methodList = new Relative(outputFilename, methodList, getCoverage, allCoverageLines).getMethodList();
            }
            orderObj = new Standard(outputFilename, methodList, getCoverage, allCoverageLines);
        } else if (order == ORDER.RANDOM) {
            Collections.shuffle(methodList);
            orderObj = new Standard(outputFilename, methodList);
        } else if (order == ORDER.ORIGINAL) {
            parseOrigOrderListToMethodList(origOrder, getNameToMethodData(methodList));
            if (randomizeOriginal) {
                Collections.shuffle(methodList);
            }
            orderObj = new Standard(outputFilename, methodList, getCoverage, allCoverageLines);
        } else {
            System.err.println("Test prioritization is specified with an incompatible order."
                    + " Compatible orders are: absolute, relative and random.");
            System.exit(0);
        }
    }

    public Standard getOrderObj() {
        return orderObj;
    }
}
