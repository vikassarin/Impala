// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.testutil;

import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Scanner;

import org.apache.log4j.Logger;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Parses a file containing one or more test case descriptions into a list of TestCase
 * objects.
 * A test file has the following format:
 *
 * <QUERY STRING>
 * ---- <SECTION NAME 1>
 * <EXPECTED CONTENTS 1>
 * ---- <SECTION NAME 2>
 * <EXPECTED CONTENTS 2>
 * ---- <SECTION NAME 3>
 * <EXPECTED CONTENTS 3>
 * ====
 * <QUERY STRING>
 * ---- <SECTION NAME 1>
 * <EXPECTED CONTENTS 1>
 * ---- <SECTION NAME 2>
 * <EXPECTED CONTENTS 2>
 * ---- <SECTION NAME 3>
 * <EXPECTED CONTENTS 3>
 * ====
 * Acceptable section names are ONLY those contained in TestFileParser.Section.
 *
 * Lines beginning with # or // are comments. Clients can retrieve sections with or
 * without these lines included.
 *
 * Note that <QUERY STRING> and <EXPECTED CONTENTS> sections can consist of multiple
 * lines.
 */
public class TestFileParser {
  private static final Logger LOG = Logger.getLogger(TestCase.class);

  /**
   * Valid section titles.
   */
  public enum Section {
    QUERY,
    TYPES,
    COLLABELS,
    RESULTS,
    PLAN,
    DISTRIBUTEDPLAN,
    FILEERRORS,
    PARTITIONS,
    SETUP,
    ERRORS,
    SCANRANGELOCATIONS;

    // Return header line for this section
    public String getHeader() {
      return "---- " + this.toString();
    }
  }

  /**
   * A container class for a single test case's sections.
   * A section is a list of strings.
   */
  public static class TestCase {

    private final EnumMap<Section, ArrayList<String>> expectedResultSections =
        Maps.newEnumMap(Section.class);

    // Line number in the test case file where this case started
    private final int startLineNum;

    public TestCase(int lineNum) {
      startLineNum = lineNum;
    }

    public int getStartingLineNum() {
      return startLineNum;
    }

    protected void addSection(Section section, ArrayList<String> contents) {
      expectedResultSections.put(section, contents);
    }

    /**
     * Returns a section corresponding to the given key, or an empty list if one does not
     * exist. Comments are not included.
     */
    public ArrayList<String> getSectionContents(Section section) {
      return getSectionContents(section, false);
    }

    public ArrayList<String> getSectionContents(Section section, boolean withComments) {
      return getSectionContents(section, withComments, null);
    }

    /**
     * Returns a section corresponding to the given key, or an empty list if one does not
     * exist.
     * @param section
     *          The Section to get
     * @param withComments
     *          If set, all comment lines are included.
     * @param dbSuffix
     *          If set, table names that contain the string $DATABASE will be replaced
     *          with the specified table suffix
     * @return Collection of strings mapping to lines in the test file
     */
    public ArrayList<String> getSectionContents(Section section, boolean withComments,
                                                String dbSuffix) {
      ArrayList<String> ret = expectedResultSections.get(section);
      if (ret == null) {
        return Lists.newArrayList();
      } else if (withComments && dbSuffix == null) {
        return ret;
      }

      ArrayList<String> retList = Lists.newArrayList();
      for (String s : ret) {
        if (!(s.startsWith("#") || s.startsWith("//"))) {
          if (dbSuffix != null) {
            retList.add(s.replaceAll("\\$DATABASE", dbSuffix));
          } else {
            retList.add(s);
          }
        } else if (withComments) {
          retList.add(s);
        }
      }

      return retList;
    }

    public String getSectionAsString(Section section, boolean withComments,
        String delimiter) {
      return getSectionAsString(section, withComments, delimiter, null);
    }

    /**
     * Returns a section concatenated into a single string, with the supplied delimiter
     * used to separate each line.
     */
    public String getSectionAsString(Section section, boolean withComments,
                                     String delimiter, String dbSuffix) {
      List<String> sectionList = getSectionContents(section, withComments, dbSuffix);
      if (sectionList == null) {
        return null;
      }

      return Joiner.on(delimiter).join(sectionList);
    }

    /**
     * Returns the QUERY section as a string, with each line separated by a " ".
     */
    public String getQuery() {
      return getSectionAsString(Section.QUERY, false, "\n");
    }

    /**
     * Returns false if the current test case is invalid due to missing sections or query
     */
    public boolean isValid() {
      return !getQuery().isEmpty() && (!getSectionContents(Section.PLAN).isEmpty() ||
          !getSectionContents(Section.DISTRIBUTEDPLAN).isEmpty());
    }
  }

  private final List<TestCase> testCases = Lists.newArrayList();

  private int lineNum = 0;
  private final String fileName;
  private BufferedReader reader;
  private Scanner scanner;
  private boolean hasSetupSection = false;

  /**
   * For backwards compatibility, if no title is found this is the order in which
   * sections are labeled.
   */
  static private final ArrayList<Section> defaultSectionOrder =
    Lists.newArrayList(Section.QUERY, Section.TYPES, Section.RESULTS);

  public TestFileParser(String fileName) {
    this.fileName = fileName;
  }

  public List<TestCase> getTestCases() {
    return testCases;
  }

  public String getTestFileName() {
    return fileName;
  }

  public boolean hasSetupSection() {
    return hasSetupSection;
  }

  /**
   * Initializes the scanner and the input stream corresponding to the test file name
   */
  private void open(String table) {
    try {
      String testFileBaseDir =
          new File(System.getenv("IMPALA_HOME"), "testdata/workloads").getPath();
      String fullPath = new File(testFileBaseDir, fileName).getPath();
      reader = new BufferedReader(new FileReader(fullPath));
      scanner = new Scanner(reader);
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  /**
   * Consumes input from the test file until a single test case has been parsed.
   */
  private TestCase parseOneTestCase() {
    Section currentSection = Section.QUERY;
    ArrayList<String> sectionContents = Lists.newArrayList();
    TestCase currentTestCase = new TestCase(lineNum);
    int sectionCount = 0;

    while (scanner.hasNextLine()) {
      String line = scanner.nextLine();
      ++lineNum;
      if (line.startsWith("====") && sectionCount > 0) {
        currentTestCase.addSection(currentSection, sectionContents);
        if(!currentTestCase.isValid()) {
          throw new IllegalStateException("Invalid test case" +
              " at line " + currentTestCase.startLineNum + " detected.");
        }
        return currentTestCase; // done with this test case
      }
      if (line.startsWith("----")) {
        sectionCount++;
        // New section
        currentTestCase.addSection(currentSection, sectionContents);
        boolean found = false;
        line = line.trim().toLowerCase();

        // Check for section header - a missing header probably means an old test file.
        if (!line.endsWith("----")) {
          for (Section s : Section.values()) {
            if (line.matches("----\\s+" + s.toString().toLowerCase() + "\\b.*")) {
              currentSection = s;
              if (s == Section.SETUP) {
                hasSetupSection = true;
              }
              found = true;
              break;
            }
          }
          if (!found) {
            throw new IllegalStateException("Unknown section name: " + line);
          }
        } else {
          // Backwards compatibility only - TODO remove once all test files have section
          // headers.
          if (sectionCount >= defaultSectionOrder.size()) {
            throw new IllegalStateException("Unexpected number of untitled sections: "
                + sectionCount);
          }
          currentSection = defaultSectionOrder.get(sectionCount);
          LOG.warn("No section header found. Guessing: " + currentSection);
        }

        sectionContents = Lists.newArrayList();
      } else {
        sectionContents.add(line);
      }
    }

    if(!currentTestCase.isValid()) {
      throw new IllegalStateException("Invalid test case" +
          " at line " + currentTestCase.startLineNum + " detected.");
    }

    return currentTestCase;
  }

  /**
   * Parses a test file in its entirety and constructs a list of TestCases.
   */
  public void parseFile() {
    parseFile(null);
  }

  public void parseFile(String table) {
    try {
      open(table);
      testCases.clear();
      while (scanner.hasNextLine()) {
        testCases.add(parseOneTestCase());
      }
    } finally {
      close();
    }
  }

  private void close() {
    if (reader != null) {
      try {
        reader.close();
      } catch (IOException e) {
        fail(e.getMessage());
      }
    }

    if (scanner != null) {
      scanner.close();
    }
  }
}
