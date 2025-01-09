/*
 * SonarQube :: SCM :: TFVC :: Plugin
 * Copyright (c) SonarSource SA and Microsoft Corporation.  All rights reserved.
 *
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */
package org.sonar.plugins.scm.tfs;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.TempFolder;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TfsBlameCommand extends BlameCommand {

  private static final String LOG_FORMAT = "{}: {}";
  private static final String LOG_PREFIX = "SCM-TFVC";
  private static final Logger LOG = LoggerFactory.getLogger(TfsBlameCommand.class);
  private static final Pattern LINE_PATTERN = Pattern.compile("([^\t]++)\t([^\t]++)\t([^\t]++)");

  private final TfsConfiguration configuration;
  private final File executable;

  @SuppressWarnings("unused") // used implicitly
  public TfsBlameCommand(TfsConfiguration conf, TempFolder temp) {
    this(conf, extractExecutable(temp));
  }

  @VisibleForTesting
  public TfsBlameCommand(TfsConfiguration configuration, File executable) {
    logDebug("started blaming with executable %s", executable.getAbsolutePath());
    if (configuration.collectionUri().isEmpty()) {
      logWarning("Missing configuration for CollectionUri. The project may not receive blame information.");
    }

    logDebug("collection uri: %s", configuration.collectionUri());
    logDebug("user name: %s", configuration.username());
    logDebug("password length: %d", configuration.password() != null ? configuration.password().length() : 0);
    logDebug("pat length: %s", configuration.pat() != null ? configuration.pat().length() : 0);

    this.configuration = configuration;
    this.executable = executable;
  }

  @SuppressWarnings({"deprecation", "squid:S1113"})
  @Override
  protected void finalize() throws Throwable {
    super.finalize();
    logDebug("blaming completed");
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    Process process = null;
    try {
      logDebug("Executing the TFVC annotate command: %s", executable.getAbsolutePath());
      ProcessBuilder processBuilder = new ProcessBuilder(executable.getAbsolutePath());
      process = processBuilder.start();
      Charset fileCharset = StandardCharsets.UTF_8;
      OutputStreamWriter stdin = new OutputStreamWriter(process.getOutputStream(), fileCharset);
      BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), fileCharset));
      BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), fileCharset));

      String blameOutput = stdout.readLine();
      for (int waitCounter=0; waitCounter<10; waitCounter++) {
        logOutput(blameOutput);
        if (!blameOutput.isEmpty()) {
          break;
        }

        Thread.sleep(100);
      }

      if (blameOutput.isEmpty()) {
        logError("missing initial output from annotator.");
        return;
      }

      stdin.write(configuration.username() + "\r\n");
      stdin.write(configuration.password() + "\r\n");
      stdin.write(configuration.pat() + "\r\n");
      stdin.flush();

      // expecting status for the connection
      blameOutput = stdout.readLine();
      logOutput(blameOutput);

      // expecting next instruction
      blameOutput = stdout.readLine();
      logOutput(blameOutput);
      stdin.write(configuration.collectionUri() + "\r\n");
      stdin.flush();

      // expecting next instruction or maybe error message
      blameOutput = stdout.readLine();
      if (blameOutput.equals("AnnotationFailedOnProject")) {
        logError(stderr.readLine());
        return;
      }

      logOutput(blameOutput);
      for (InputFile inputFile : input.filesToBlame()) {
    	// extract full path from URI, skipping leading slash
    	String fileName = inputFile.uri().getPath().substring(1);
        logInfo("annotating %s", fileName);

        stdin.write(fileName + "\r\n");
        stdin.flush();

        String path = stdout.readLine();
        if (!fileName.equals(path)) {
          throw new IllegalStateException("Expected the file paths to match: " + fileName + " and " + path);
        }

        String linesAsString = stdout.readLine();
        if (linesAsString.equals("AnnotationFailedOnFile")) {
          logError(stderr.readLine());
          continue;
        }

        if (linesAsString.equals("AnnotationFailedOnProject")) {
          logError(stderr.readLine());
          break;
        }

        int lines = Integer.parseInt(linesAsString, 10);
        List<BlameLine> result = Lists.newArrayList();
        for (int i = 0; i < lines; i++) {
          String line = stdout.readLine();

          Matcher matcher = LINE_PATTERN.matcher(line);
          if (!matcher.find()) {
            throw new IllegalStateException("Invalid output from the TFVC annotate command: \"" + line + "\" on file: " + path + " at line " + (i + 1));
          }

          String revision = matcher.group(1).trim();
          String author = matcher.group(2).trim();
          String dateStr = matcher.group(3).trim();

          Date date = new Date(Long.parseLong(dateStr, 10));

          result.add(new BlameLine().date(date).revision(revision).author(author));
        }

        if (result.size() == inputFile.lines() - 1) {
          // SONARPLUGINS-3097 TFS do not report blame on last empty line
          result.add(result.get(result.size() - 1));
        }

        output.blameResult(inputFile, result);
        captureErrorStream(process);
      }

      stdin.close();

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException("The TFVC annotate command " + executable.getAbsolutePath() + " failed with exit code " + exitCode);
      }
    } catch (IOException e) {
      logError("IOException thrown in the TFVC annotate command: %s", e.getMessage());
    } catch (InterruptedException e) {
      logError("InterruptedException thrown in the TFVC annotate command: %s", e.getMessage());
      // Restore interrupted state...
      Thread.currentThread().interrupt();
    } catch (IllegalStateException e) {
      logError("IllegalStateException thrown in the TFVC annotate command: %s", e.getMessage());
    } finally {
      if (process != null) {
        captureErrorStream(process);
        try {
          process.getInputStream().close();
          process.getOutputStream().close();
          process.getErrorStream().close();
        }
        catch (IOException e) {
          // just ignore
        }
        process.destroy();
      }
    }
  }

  private static void logInfo(String message, Object... arguments) {
    String fullMessage = String.format(message, arguments);
	  LOG.info(LOG_FORMAT, LOG_PREFIX, fullMessage);
  }

  private static void logDebug(String message, Object... arguments) {
    String fullMessage = String.format(message, arguments);
    LOG.debug(LOG_FORMAT, LOG_PREFIX, fullMessage);
  }

  private static void logWarning(String message, Object... arguments) {
    String fullMessage = String.format(message, arguments);
    LOG.warn(LOG_FORMAT, LOG_PREFIX, fullMessage);
  }

  private static void logError(String message, Object... arguments) {
    String fullMessage = String.format(message, arguments);
    LOG.error(LOG_FORMAT, LOG_PREFIX, fullMessage);
  }

  private static void logOutput(String output) {
    logDebug("received output: <%s>", output);
  }

  private static void captureErrorStream(Process process) {
    try {
      InputStream errorStream = process.getErrorStream();
      BufferedReader errStream = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
      int readBytesCount = errorStream.available();
      char[] errorChars = new char[readBytesCount];

      if (readBytesCount > 0) {
        errStream.read(errorChars);
        String errorString = new String(errorChars);
        if (!errorString.isEmpty()) {
          logError(errorString);
        }
      }
    } catch (IOException e) {
      logError("Exception thrown while getting error Stream data: %s", e);
    }
  }

  private static File extractExecutable(TempFolder temp) {
    File executable = temp.newFile("SonarTfsAnnotate", ".exe");
    try {
      URL resource = TfsBlameCommand.class.getResource("/SonarTfsAnnotate.exe");
      Files.write(Resources.toByteArray(Objects.requireNonNull(resource)), executable);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to extract SonarTfsAnnotate.exe", e);
    }
    return executable;
  }
}
