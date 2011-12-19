/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.appserver.websphere;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.TestConfigObject;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.util.AppServerUtil;
import com.tc.text.Banner;
import com.tc.util.PortChooser;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class WebsphereAppServer extends AbstractAppServer {

  private static final String TERRACOTTA_PY              = "terracotta.py";
  private static final String DEPLOY_APPS_PY             = "deployApps.py";
  private static final String ENABLE_DSO_PY              = "enable-dso.py";
  private static final String DSO_JVMARGS                = "__DSO_JVMARGS__";
  private static final String PORTS_DEF                  = "ports.def";
  private static final int    START_STOP_TIMEOUT_SECONDS = 5 * 60;

  private final String[]      scripts                    = new String[] { DEPLOY_APPS_PY, TERRACOTTA_PY, ENABLE_DSO_PY };

  private final String        policy                     = "grant codeBase \"file:FILENAME\" {"
                                                           + IOUtils.LINE_SEPARATOR
                                                           + "  permission java.security.AllPermission;"
                                                           + IOUtils.LINE_SEPARATOR + "};" + IOUtils.LINE_SEPARATOR;
  private String              instanceName;
  private String              dsoJvmArgs;
  private int                 webspherePort;
  private File                sandbox;
  private File                instanceDir;
  private File                pyScriptsDir;
  private File                dataDir;
  private File                warDir;
  private File                portDefFile;
  private File                serverInstallDir;
  private File                extraScript;

  private Thread              serverThread;

  public WebsphereAppServer(AppServerInstallation installation) {
    super(installation);
  }

  public ServerResult start(ServerParameters parameters) throws Exception {
    init(parameters);
    createPortFile();
    copyPythonScripts();
    patchTerracottaPy();
    deleteProfileIfExists();
    createProfile();
    verifyProfile();
    deployWebapps();
    addTerracottaToServerPolicy();
    enableDSO();
    if (extraScript != null) {
      executeJythonScript(extraScript);
    }
    serverThread = new Thread() {
      @Override
      public void run() {
        try {
          startWebsphere();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    serverThread.setDaemon(true);
    serverThread.start();
    AppServerUtil.waitForPort(webspherePort, START_STOP_TIMEOUT_SECONDS * 1000);
    System.out.println("Websphere instance " + instanceName + " started on port " + webspherePort);
    return new AppServerResult(webspherePort, this);
  }

  public void stop(ServerParameters parameters) throws Exception {
    try {
      stopWebsphere();
      // ws lie about shuttting down completely. Sleep to give it
      // some time to kill the process
      Thread.sleep(5 * 1000);
    } catch (Exception e) {
      // don't fail the test by rethrowing
      e.printStackTrace();
    } finally {
      // copy the terracotta client log files into a place that won't be destroy when profile is deleted
      copyClientLogs();

      try {
        deleteProfile();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private void copyClientLogs() {
    File srcDir = new File(instanceDir, "terracotta");

    if (srcDir.isDirectory()) {
      File dstDir = new File(instanceDir, "logs");
      if (dstDir.isDirectory()) {
        try {
          FileUtils.copyDirectoryToDirectory(srcDir, dstDir);
        } catch (IOException e) {
          e.printStackTrace();
        }
      } else {
        Banner.warnBanner(dstDir + " is not a directory?");
      }
    } else {
      Banner.warnBanner(srcDir + " is not a directory?");
    }
  }

  private void createPortFile() throws Exception {
    PortChooser portChooser = new PortChooser();
    webspherePort = portChooser.chooseRandomPort();

    List lines = IOUtils.readLines(WebsphereAppServer.class.getResourceAsStream(PORTS_DEF));
    lines.set(0, (String) lines.get(0) + webspherePort);

    for (int i = 1; i < lines.size(); i++) {
      String line = (String) lines.get(i);
      lines.set(i, line + portChooser.chooseRandomPort());
    }

    writeLines(lines, portDefFile, false);
  }

  private void copyPythonScripts() throws Exception {
    for (String script : scripts) {
      System.out.println("copyPythonScripts(): copying file[" + script + "] to directory [" + pyScriptsDir + "]");
      copyResourceTo(script, new File(pyScriptsDir, script));
    }
  }

  private void enableDSO() throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        new File(pyScriptsDir, ENABLE_DSO_PY).getAbsolutePath() };
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error in enabling DSO for " + instanceName);
  }

  private void patchTerracottaPy() throws FileNotFoundException, IOException, Exception {
    File terracotta_py = new File(pyScriptsDir, TERRACOTTA_PY);
    FileInputStream fin = new FileInputStream(terracotta_py);
    List lines = IOUtils.readLines(fin);
    fin.close();

    // replace __DSO_JVMARGS__
    for (int i = 0; i < lines.size(); i++) {
      String line = (String) lines.get(i);
      if (line.indexOf(DSO_JVMARGS) >= 0) {
        line = line.replaceFirst(DSO_JVMARGS, dsoJvmArgs);
        lines.set(i, line);
      }
    }

    writeLines(lines, terracotta_py, false);
  }

  private void executeJythonScript(File script) throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        script.getAbsolutePath() };
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error executing " + script);
  }

  private void deleteProfile() throws Exception {
    String[] args = new String[] { "-delete", "-profileName", instanceName };
    executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Error in deleting profile for "
                                                                               + instanceName);
  }

  private void createProfile() throws Exception {
    String defaultTemplate = new File(serverInstallDir.getAbsolutePath(), "profileTemplates/default").getAbsolutePath();
    String[] args = new String[] { "-create", "-templatePath", defaultTemplate, "-profileName", instanceName,
        "-profilePath", instanceDir.getAbsolutePath(), "-portsFile", portDefFile.getAbsolutePath(),
        "-enableAdminSecurity", "false" }; // , "-isDeveloperServer"
    System.out.println("Creating profile for instance " + instanceName + "...");
    long start = System.currentTimeMillis();
    String output = executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir,
                                   "Error in creating profile for " + instanceName);

    // there's still a chance websphere misreports profile doesn't exist, we try deleting it and recreate one more time
    if (output.contains("is already in use. Specify another name")) {
      deleteProfile();
      executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Error in creating profile for "
                                                                                 + instanceName);
    }

    long elapsedMillis = System.currentTimeMillis() - start;
    long elapsedSeconds = elapsedMillis / 1000;
    Long elapsedMinutes = new Long(elapsedSeconds / 60);
    System.out.println("Profile creation time: "
                       + MessageFormat.format("{0,number,##}:{1}.{2}", new Object[] { elapsedMinutes,
                           new Long(elapsedSeconds % 60), new Long(elapsedMillis % 1000) }));
  }

  private void verifyProfile() throws Exception {
    if (!(instanceDir.exists() && instanceDir.isDirectory())) {
      Exception e = new Exception("Unable to verify profile for instance '" + instanceName + "'");
      System.err.println("WebSphere profile '" + instanceName + "' does not exist at " + instanceDir.getAbsolutePath());
      throw e;
    }
    System.out.println("WebSphere profile '" + instanceName + "' is verified at " + instanceDir.getAbsolutePath());
  }

  private void deleteProfileIfExists() throws Exception {
    // call "manageprofiles.sh -validateAndUpdateRegistry" to clean out corrupted profiles
    String[] args = new String[] { "-validateAndUpdateRegistry" };
    executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "");
    args = new String[] { "-listProfiles" };
    String output = executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "");
    if (output.indexOf(instanceName) >= 0) {
      args = new String[] { "-delete", "-profileName", instanceName };
      executeCommand(serverInstallDir, "manageprofiles", args, serverInstallDir, "Trying to clean up existing profile");
    }
  }

  private void addTerracottaToServerPolicy() throws Exception {
    String classpath = System.getProperty("java.class.path");
    Set set = new HashSet();
    String[] entries = classpath.split(File.pathSeparator);
    for (String entrie : entries) {
      File filename = new File(entrie);
      if (filename.isDirectory()) {
        set.add(filename);
      } else {
        set.add(filename.getParentFile());
      }
    }

    List lines = new ArrayList(set.size() + 1);
    for (Iterator it = set.iterator(); it.hasNext();) {
      lines.add(getPolicyFor((File) it.next()));
    }
    lines.add(getPolicyFor(new File(TestConfigObject.getInstance().normalBootJar())));

    writeLines(lines, new File(new File(instanceDir, "properties"), "server.policy"), true);
  }

  private String getPolicyFor(File filename) {
    String entry = filename.getAbsolutePath().replace('\\', '/');

    if (filename.isDirectory()) {
      return policy.replaceFirst("FILENAME", entry + "/-");
    } else {
      return policy.replaceFirst("FILENAME", entry);
    }
  }

  private void copyResourceTo(String filename, File dest) throws Exception {
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(dest);
      IOUtils.copy(WebsphereAppServer.class.getResourceAsStream(filename), fos);
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  private void deployWebapps() throws Exception {
    String[] args = new String[] { "-lang", "jython", "-connType", "NONE", "-profileName", instanceName, "-f",
        new File(pyScriptsDir, DEPLOY_APPS_PY).getAbsolutePath(), warDir.getAbsolutePath().replace('\\', '/') };
    System.out.println("Deploying war file in: " + warDir);
    executeCommand(serverInstallDir, "wsadmin", args, pyScriptsDir, "Error in deploying warfile for " + instanceName);
    System.out.println("Done deploying war file in: " + warDir);
  }

  private void startWebsphere() throws Exception {
    String[] args = new String[] { "server1", "-profileName", instanceName, "-trace", "-timeout",
        String.valueOf(START_STOP_TIMEOUT_SECONDS) };
    executeCommand(serverInstallDir, "startServer", args, instanceDir, "Error in starting " + instanceName);
  }

  private void stopWebsphere() throws Exception {
    String[] args = new String[] { "server1", "-profileName", instanceName };
    executeCommand(serverInstallDir, "stopServer", args, instanceDir, "Error in stopping " + instanceName);
    if (serverThread != null) {
      serverThread.join(START_STOP_TIMEOUT_SECONDS * 1000);
    }
  }

  private void init(ServerParameters parameters) {
    AppServerParameters params = (AppServerParameters) parameters;
    sandbox = sandboxDirectory();
    instanceName = params.instanceName();
    instanceDir = new File(sandbox, instanceName);
    dataDir = new File(sandbox, "data");
    warDir = new File(sandbox, "war");
    pyScriptsDir = new File(dataDir, instanceName);
    pyScriptsDir.mkdirs();
    portDefFile = new File(pyScriptsDir, PORTS_DEF);
    serverInstallDir = serverInstallDirectory();

    String[] jvm_args = params.jvmArgs().replaceAll("'", "").replace('\\', '/').split("\\s+");
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < jvm_args.length; i++) {
      sb.append("\"" + jvm_args[i] + "\"");
      if (i < jvm_args.length - 1) {
        sb.append(", ");
      }
    }
    dsoJvmArgs = sb.toString();

    System.out.println("init{sandbox}          ==> " + sandbox.getAbsolutePath());
    System.out.println("init{instanceName}     ==> " + instanceName);
    System.out.println("init{instanceDir}      ==> " + instanceDir.getAbsolutePath());
    System.out.println("init{webappDir}        ==> " + dataDir.getAbsolutePath());
    System.out.println("init{pyScriptsDir}     ==> " + pyScriptsDir.getAbsolutePath());
    System.out.println("init{portDefFile}      ==> " + portDefFile.getAbsolutePath());
    System.out.println("init{serverInstallDir} ==> " + serverInstallDir.getAbsolutePath());
    System.out.println("init{dsoJvmArgs}       ==> " + dsoJvmArgs);
  }

  private String getScriptPath(File root, String scriptName) {
    File bindir = new File(root, "bin");
    return new File(bindir, (Os.isWindows() ? scriptName + ".bat" : scriptName + ".sh")).getAbsolutePath();
  }

  private String executeCommand(File rootDir, String scriptName, String[] args, File workingDir, String errorMessage)
      throws Exception {
    String script = getScriptPath(rootDir, scriptName);
    String[] cmd = new String[args.length + 1];
    cmd[0] = script;
    System.arraycopy(args, 0, cmd, 1, args.length);
    System.out.println("Executing cmd: " + Arrays.asList(cmd));
    Result result = Exec.execute(cmd, null, null, workingDir == null ? instanceDir : workingDir);
    final StringBuffer stdout = new StringBuffer(result.getStdout());
    final StringBuffer stderr = new StringBuffer(result.getStderr());

    if (result.getExitCode() != 0) {
      System.out.println("Command did not return 0; message is: " + errorMessage);
    }
    String output = stdout.append(IOUtils.LINE_SEPARATOR).append(stderr).toString();
    System.out.println("output: " + output);
    return output;
  }

  private void writeLines(List lines, File filename, boolean append) throws Exception {
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(filename, append);
      IOUtils.writeLines(lines, IOUtils.LINE_SEPARATOR, fos);
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  public void setExtraScript(File extraScript) {
    this.extraScript = extraScript;
  }
}
