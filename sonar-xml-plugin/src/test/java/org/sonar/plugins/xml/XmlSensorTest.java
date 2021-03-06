/*
 * SonarQube XML Plugin
 * Copyright (C) 2010-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.xml;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.assertj.core.api.Condition;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.FileMetadata;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.internal.apachecommons.io.FileUtils;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.LogTester;
import org.sonar.plugins.xml.checks.XmlIssue;
import org.sonar.plugins.xml.checks.XmlSourceCode;
import org.sonar.plugins.xml.language.Xml;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class XmlSensorTest extends AbstractXmlPluginTester {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public LogTester logTester = new LogTester();

  private DefaultFileSystem fs;
  private XmlSensor sensor;
  private SensorContextTester context;

  private final RuleKey ruleKey = RuleKey.of(Xml.REPOSITORY_KEY, "NewlineCheck");

  private final String parsingErrorCheckKey = "S2260";

  private final RuleKey parsingErrorCheckRuleKey = RuleKey.of(Xml.REPOSITORY_KEY, parsingErrorCheckKey);

  @Test(timeout = 10000)
  public void testPerformance() throws Exception {
    initFileSystemWithFile(createXmlFile(20000, "smallFile.xml"));
    long timeSmallFile = measureTimeToAnalyzeFile();
    initFileSystemWithFile(createXmlFile(40000, "bigFile.xml"));
    long timeBigFile = measureTimeToAnalyzeFile();
    assertThat(timeBigFile < (2.5 * timeSmallFile)).isTrue();
  }

  /**
   * Expect issue for rule: NewlineCheck
   */
  @Test
  public void testSensor() throws Exception {
    init(false);
    fs.add(createInputFile("src/pom.xml"));

    sensor.execute(context);

    assertThat(context.allIssues()).extracting("ruleKey").containsOnly(ruleKey);
  }

  /**
   * SONARXML-19
   * Expect issue for rule: NewlineCheck
   */
  @Test
  public void should_execute_on_file_with_chars_before_prolog() throws Exception {
    init(false);
    fs.add(createInputFile("checks/generic/pom_with_chars_before_prolog.xml"));

    sensor.execute(context);

    assertThat(context.allIssues()).extracting("ruleKey").containsOnly(ruleKey);
  }

  /**
   * Has issue for rule NewlineCheck, but should not be reported.
   * As rule ParsingErrorCheck is enabled, this test should report a parsing issue. It should also log a trace.
   */
  @Test
  public void should_not_execute_test_on_corrupted_file_and_should_raise_parsing_issue() throws Exception {
    init(true);
    fs.add(createInputFile("checks/generic/wrong-ampersand.xhtml"));

    sensor.execute(context);

    assertThat(context.allIssues()).hasSize(1);
    Issue issue = context.allIssues().iterator().next();
    assertThat(issue.ruleKey().rule()).isEqualTo(parsingErrorCheckKey);

    assertLog("Unable to parse file .*wrong-ampersand.*", true);
    assertLog("Cause: org.xml.sax.SAXParseException.* Element type \"as\\.length\" must be followed by either attribute specifications, .*", true);
  }

  /**
   * Has issue for rule NewlineCheck, but should not be reported.
   * As rule ParsingErrorCheck is not enabled, this test should not report any issue. It should log a trace instead.
   */
  @Test
  public void should_not_execute_test_on_corrupted_file_and_should_not_raise_parsing_issue() throws Exception {
    init(false);
    fs.add(createInputFile("checks/generic/wrong-ampersand.xhtml"));

    sensor.execute(context);

    assertThat(context.allIssues()).isEmpty();

    assertLog("Unable to parse file .*wrong-ampersand.*", true);
    assertLog("Cause: org.xml.sax.SAXParseException.* Element type \"as\\.length\" must be followed by either attribute specifications, .*", true);
  }

  private void init(boolean activateParsingErrorCheck) throws Exception {
    File moduleBaseDir = new File("src/test/resources");
    context = SensorContextTester.create(moduleBaseDir);

    fs = new DefaultFileSystem(moduleBaseDir);
    fs.setWorkDir(temporaryFolder.newFolder("temp").toPath());

    ActiveRules activeRules = null;
    if (activateParsingErrorCheck) {
      activeRules = new ActiveRulesBuilder()
        .create(ruleKey)
        .activate()
        .create(parsingErrorCheckRuleKey)
        .setInternalKey(parsingErrorCheckKey)
        .activate()
        .build();
    } else {
      activeRules = new ActiveRulesBuilder()
        .create(ruleKey)
        .activate()
        .build();
    }
    CheckFactory checkFactory = new CheckFactory(activeRules);

    FileLinesContextFactory fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(mock(FileLinesContext.class));

    sensor = new XmlSensor(fs, checkFactory, fileLinesContextFactory);
  }

  private DefaultInputFile createInputFile(String name) throws FileNotFoundException {
    DefaultInputFile inputFile = TestInputFileBuilder.create("modulekey", name)
      .setModuleBaseDir(Paths.get("src/test/resources"))
      .setType(Type.MAIN)
      .setLanguage(Xml.KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();

    inputFile.setMetadata(new FileMetadata().readMetadata(new FileInputStream(inputFile.file()), StandardCharsets.UTF_8, inputFile.absolutePath()));
    return inputFile;
  }

  @Test
  public void should_store_issue_with_no_line() throws Exception {
    init(false);

    XmlSourceCode sourceCode = mock(XmlSourceCode.class);
    XmlIssue issueWithNoLine = new XmlIssue(RuleKey.parse("SomeRepo:SomeCheck"), null, "Hello, the line is null");
    when(sourceCode.getXmlIssues()).thenReturn(singletonList(issueWithNoLine));
    when(sourceCode.getInputFile()).thenReturn(createInputFile("src/pom.xml"));  // any file fits

    sensor.saveIssue(context, sourceCode);

    assertThat(context.allIssues()).hasSize(1);
    Issue issue = context.allIssues().iterator().next(); 
    assertThat(issue.primaryLocation().textRange()).isNull();
  }

  @Test
  public void should_analyze_file_with_its_own_encoding() throws IOException {
    Charset fileSystemCharset = StandardCharsets.UTF_8;
    Charset fileCharset = StandardCharsets.UTF_16;

    Path moduleBaseDir = temporaryFolder.newFolder().toPath();
    SensorContextTester context = SensorContextTester.create(moduleBaseDir);

    DefaultFileSystem fileSystem = new DefaultFileSystem(moduleBaseDir);
    fileSystem.setEncoding(fileSystemCharset);
    context.setFileSystem(fileSystem);
    String filename = "utf16.xml";
    try (BufferedWriter writer = Files.newBufferedWriter(moduleBaseDir.resolve(filename), fileCharset)) {
      writer.write("<?xml version=\"1.0\" encoding=\"utf-16\" standalone=\"yes\"?>\n");
      writer.write("<tag></tag>");
    }

    String modulekey = "modulekey";
    DefaultInputFile defaultInputFile = TestInputFileBuilder.create(modulekey, filename)
      .setModuleBaseDir(moduleBaseDir)
      .setType(InputFile.Type.MAIN)
      .setLanguage(Xml.KEY)
      .setCharset(fileCharset)
      .build();
    fileSystem.add(defaultInputFile);

    defaultInputFile.setMetadata(new FileMetadata().readMetadata(new FileInputStream(defaultInputFile.file()), StandardCharsets.UTF_8, defaultInputFile.absolutePath()));

    ActiveRules activeRules = new ActiveRulesBuilder()
      .create(ruleKey)
      .activate()
      .build();
    CheckFactory checkFactory = new CheckFactory(activeRules);

    FileLinesContextFactory fileLinesContextFactory = mock(FileLinesContextFactory.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(mock(FileLinesContext.class));
    sensor = new XmlSensor(fileSystem, checkFactory, fileLinesContextFactory);
    sensor.execute(context);

    String componentKey = modulekey + ":" + filename;
    assertThat(context.measure(componentKey, CoreMetrics.NCLOC).value()).isEqualTo(2);
  }

  private void assertLog(String expected, boolean isRegexp) {
    if (isRegexp) {
      Condition<String> regexpMatches = new Condition<String>(log -> Pattern.compile(expected).matcher(log).matches(), "");
      assertThat(logTester.logs())
        .filteredOn(regexpMatches)
        .as("None of the lines in " + logTester.logs() + " matches regexp [" + expected + "], but one line was expected to match")
        .isNotEmpty();
    } else {
      assertThat(logTester.logs()).contains(expected);
    }
  }

  private void initFileSystemWithFile(File file) throws Exception {
    init(false);

    DefaultInputFile inputFile = TestInputFileBuilder.create("modulekey", file.getName())
      .setModuleBaseDir(Paths.get(file.getParent()))
      .setType(Type.MAIN)
      .setLanguage(Xml.KEY)
      .setCharset(StandardCharsets.UTF_8)
      .build();

    inputFile.setMetadata(new FileMetadata().readMetadata(new FileInputStream(inputFile.file()), StandardCharsets.UTF_8, inputFile.absolutePath()));

    fs.add(inputFile);
  }

  private File createXmlFile(int numberOfTags, String fileName) throws IOException {
    File file = temporaryFolder.newFile(fileName);
    StringBuilder str = new StringBuilder("<?xml version=\"1.0\"?><root>\n");
    IntStream.range(0, numberOfTags).forEach(iteration -> str.append("<tag1 attr=\"val1\">text</tag1>\n"));
    str.append("</root>");
    FileUtils.write(file, str.toString());
    return file;
  }

  private long measureTimeToAnalyzeFile() {
    long t1 = System.currentTimeMillis();
    sensor.execute(context);
    return System.currentTimeMillis() - t1;
  }

}
