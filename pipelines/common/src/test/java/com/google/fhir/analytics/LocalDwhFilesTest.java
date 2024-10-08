/*
 * Copyright 2020-2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import ca.uhn.fhir.context.FhirContext;
import com.google.common.io.Resources;
import com.google.fhir.analytics.view.ViewDefinitionException;
import com.google.fhir.analytics.view.ViewManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class LocalDwhFilesTest {
  @Test
  public void getResourcePathTestNonWindows() {
    Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
    DwhFiles dwhFiles = new DwhFiles("/tmp", FhirContext.forR4Cached());
    assertThat(dwhFiles.getResourcePath("Patient").toString(), equalTo("/tmp/Patient/"));
  }

  @Test
  public void getResourcePathTestWindows() {
    Assume.assumeTrue(SystemUtils.IS_OS_WINDOWS);
    DwhFiles dwhFiles = new DwhFiles("C:\\tmp", FhirContext.forR4Cached());
    assertThat(dwhFiles.getResourcePath("Patient").toString(), equalTo("C:\\tmp\\Patient\\"));
  }

  @Test
  public void newIncrementalRunPathTestNonWindows() throws IOException {
    Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
    DwhFiles instance = new DwhFiles("/tmp", FhirContext.forR4Cached());
    ResourceId incrementalRunPath = instance.newIncrementalRunPath();
    assertThat(incrementalRunPath.toString(), equalTo("/tmp/incremental_run/"));
  }

  @Test
  public void newIncrementalRunPathTesWindows() throws IOException {
    Assume.assumeTrue(SystemUtils.IS_OS_WINDOWS);
    DwhFiles instance = new DwhFiles("C:\\tmp", FhirContext.forR4Cached());
    ResourceId incrementalRunPath = instance.newIncrementalRunPath();
    assertThat(incrementalRunPath.toString(), equalTo("C:\\tmp\\incremental_run\\"));
  }

  @Test
  public void findNonEmptyFhirResourceTypesTest() throws IOException {
    Path root = Files.createTempDirectory("DWH_FILES_TEST");
    DwhFiles instance = new DwhFiles(root.toString(), FhirContext.forR4Cached());
    Path patientPath = Paths.get(root.toString(), "Patient");
    Files.createDirectories(patientPath);
    createFile(
        Paths.get(patientPath.toString(), "patients.txt"),
        "SAMPLE TEXT".getBytes(StandardCharsets.UTF_8));

    Path observationPath = Paths.get(root.toString(), "Observation");
    Files.createDirectories(observationPath);
    createFile(
        Paths.get(observationPath.toString(), "observationPath.txt"),
        "SAMPLE TEXT".getBytes(StandardCharsets.UTF_8));

    Set<String> resourceTypes = instance.findNonEmptyResourceDirs();
    assertThat("Could not find Patient", resourceTypes.contains("Patient"));
    assertThat("Could not find Observation", resourceTypes.contains("Observation"));
    assertThat(resourceTypes.size(), equalTo(2));

    Files.delete(Paths.get(observationPath.toString(), "observationPath.txt"));
    Files.delete(observationPath);
    Files.delete(Paths.get(patientPath.toString(), "patients.txt"));
    Files.delete(patientPath);
    Files.delete(root);
  }

  @Test
  public void findNonEmptyViewDirectoriesTest() throws IOException, ViewDefinitionException {
    Path root = Files.createTempDirectory("DWH_FILES_TEST");
    DwhFiles instance = new DwhFiles(root.toString(), FhirContext.forR4Cached());
    String path = Resources.getResource("parquet-util-view-test").getFile();
    ViewManager viewManager = ViewManager.createForDir(path);

    Path obsPath = Paths.get(root.toString(), "observation_flat");
    Files.createDirectories(obsPath);
    Path testPath = Paths.get(root.toString(), "test_dir");
    Files.createDirectories(testPath);

    String viewFileName =
        "Patient_main_patient_flat_output-parquet-th-112-ts-1724089542269-r-195410.parquet";
    createFile(
        Paths.get(obsPath.toString(), viewFileName),
        "Sample Text".getBytes(StandardCharsets.UTF_8));

    Path observationPath = Paths.get(root.toString(), "Observation");
    Files.createDirectories(observationPath);
    createFile(
        Paths.get(observationPath.toString(), "observationPath.txt"),
        "SAMPLE TEXT".getBytes(StandardCharsets.UTF_8));

    Set<String> resourceTypes = instance.findNonEmptyViewDirs(viewManager);
    assertThat("Could not find Patient", resourceTypes.contains("observation_flat"));
    assertThat("Could not find Observation", !resourceTypes.contains("Observation"));
    assertThat("Could not find Test Directory!", !resourceTypes.contains("test_dir"));
    assertThat(resourceTypes.size(), equalTo(1));

    Files.delete(Paths.get(observationPath.toString(), "observationPath.txt"));
    Files.delete(observationPath);
    Files.delete(Paths.get(obsPath.toString(), viewFileName));
    Files.delete(obsPath);
    Files.delete(testPath);
    Files.delete(root);
  }

  @Test
  public void copyResourceTypeTest() throws IOException {
    Path sourcePath = Files.createTempDirectory("DWH_SOURCE_TEST");
    FhirContext fhirContext = FhirContext.forR4Cached();
    DwhFiles instance = new DwhFiles(sourcePath.toString(), fhirContext);
    Path patientPath = Paths.get(sourcePath.toString(), "Patient");
    Files.createDirectories(patientPath);
    createFile(
        Paths.get(patientPath.toString(), "patients.txt"),
        "SAMPLE TEXT".getBytes(StandardCharsets.UTF_8));

    Path destPath = Files.createTempDirectory("DWH_DEST_TEST");
    instance.copyResourcesToDwh("Patient", DwhFiles.forRoot(destPath.toString(), fhirContext));

    List<Path> destFiles = Files.list(destPath).collect(Collectors.toList());
    assertThat(destFiles.size(), equalTo(1));
    assertThat(destFiles.get(0).toString(), equalTo(destPath.resolve("Patient").toString()));

    List<Path> destChildFiles = Files.list(destFiles.get(0)).collect(Collectors.toList());
    assertThat(destChildFiles.size(), equalTo(1));
    assertThat(
        destChildFiles.get(0).toString(),
        equalTo(destFiles.get(0).resolve("patients.txt").toString()));

    Files.delete(Paths.get(destPath.resolve("Patient").toString(), "patients.txt"));
    Files.delete(Paths.get(destPath.resolve("Patient").toString()));
    Files.delete(Paths.get(patientPath.toString(), "patients.txt"));
    Files.delete(Paths.get(patientPath.toString()));
    Files.delete(destPath);
    Files.delete(sourcePath);
  }

  @Test
  public void writeTimestampFile_FileAlreadyExists_ThrowsError() throws IOException {
    Path root = Files.createTempDirectory("DWH_FILES_TEST");
    Path timestampPath = Paths.get(root.toString(), "timestamp_start.txt");
    createFile(timestampPath, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    DwhFiles dwhFiles = new DwhFiles(root.toString(), FhirContext.forR4Cached());

    Assert.assertThrows(
        FileAlreadyExistsException.class,
        () -> dwhFiles.writeTimestampFile(DwhFiles.TIMESTAMP_FILE_START));

    Files.delete(timestampPath);
    Files.delete(root);
  }

  @Test
  public void writeTimestampFile_FileDoesNotExist_CreatesFile() throws IOException {
    Path root = Files.createTempDirectory("DWH_FILES_TEST");
    DwhFiles dwhFiles = new DwhFiles(root.toString(), FhirContext.forR4Cached());

    dwhFiles.writeTimestampFile(DwhFiles.TIMESTAMP_FILE_START);

    List<Path> destFiles = Files.list(root).collect(Collectors.toList());
    assertThat(destFiles.size(), equalTo(1));
    assertThat(
        destFiles.get(0).toString(), equalTo(root.resolve("timestamp_start.txt").toString()));

    Files.delete(destFiles.get(0));
    Files.delete(root);
  }

  @Test
  public void readTimestampFile() throws IOException {
    Path root = Files.createTempDirectory("DWH_FILES_TEST");
    Instant currentInstant = Instant.now();
    Path timestampPath = Paths.get(root.toString(), "timestamp_start.txt");
    createFile(timestampPath, currentInstant.toString().getBytes(StandardCharsets.UTF_8));
    DwhFiles dwhFiles = new DwhFiles(root.toString(), FhirContext.forR4Cached());

    Instant actualInstant = dwhFiles.readTimestampFile(DwhFiles.TIMESTAMP_FILE_START);

    Assert.assertEquals(currentInstant.getEpochSecond(), actualInstant.getEpochSecond());

    Files.delete(timestampPath);
    Files.delete(root);
  }

  @Test
  public void passNonWindowsLocalPathDwhRootPrefix_returnsFileSeparator() {
    Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
    // Absolute Path
    String fs1 = DwhFiles.getFileSeparatorForDwhFiles("/rootDir/prefix");
    Assert.assertEquals(File.separator, fs1);
    // Relative Path
    String fs2 = DwhFiles.getFileSeparatorForDwhFiles("baseDir/prefix");
    Assert.assertEquals(File.separator, fs2);
  }

  @Test
  public void passWindowsLocalPathDwhRootPrefix_returnsFileSeparator() {
    Assume.assumeTrue(SystemUtils.IS_OS_WINDOWS);
    // Absolute Path
    String fs1 = DwhFiles.getFileSeparatorForDwhFiles("C:\\prefix");
    Assert.assertEquals(File.separator, fs1);
    String fs2 = DwhFiles.getFileSeparatorForDwhFiles("C:\\rootDir\\prefix");
    Assert.assertEquals(File.separator, fs2);
    // Relative Path
    String fs3 = DwhFiles.getFileSeparatorForDwhFiles("baseDir\\prefix");
    Assert.assertEquals(File.separator, fs3);
  }

  private void createFile(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes);
  }
}
