/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.appserver.websphere;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Replace;

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
import com.tc.util.StringUtil;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class WebsphereAppServer extends AbstractAppServer {
  private static volatile boolean resetRepo;
  private static final String     TERRACOTTA_PY              = "terracotta.py";
  private static final String     DEPLOY_APPS_PY             = "deployApps.py";
  private static final String     ENABLE_DSO_PY              = "enable-dso.py";
  private static final String     DSO_JVMARGS                = "__DSO_JVMARGS__";
  private static final int        START_STOP_TIMEOUT_SECONDS = 10 * 60;

  private final String[]          scripts                    = new String[] { DEPLOY_APPS_PY, TERRACOTTA_PY,
      ENABLE_DSO_PY                                         };

  private final String            policy                     = "grant codeBase \"file:FILENAME\" {"
                                                               + IOUtils.LINE_SEPARATOR
                                                               + "  permission java.security.AllPermission;"
                                                               + IOUtils.LINE_SEPARATOR + "};" + IOUtils.LINE_SEPARATOR;
  private String                  instanceName;
  private String                  dsoJvmArgs;
  private int                     webspherePort;
  private File                    sandbox;
  private File                    instanceDir;
  private File                    pyScriptsDir;
  private File                    dataDir;
  private File                    warDir;
  private File                    serverInstallDir;
  private File                    extraScript;
  private File                    profileRepo;
  private File                    profileResistry;
  private String                  processId;

  private Thread                  serverThread;

  public WebsphereAppServer(AppServerInstallation installation) {
    super(installation);
  }

  @Override
  public ServerResult start(ServerParameters parameters) throws Exception {
    init(parameters);
    reset();
    copyPythonScripts();
    patchTerracottaPy();
    createProfile();
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

  @Override
  public void stop(ServerParameters parameters) throws Exception {
    try {
      stopWebsphere();
      // ws lie about shuttting down completely. Sleep to give it
      // some time to kill the process
      Thread.sleep(3 * 1000);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      forceKill();
      copyOperationLogs();
      cleanup();
    }
  }

  private void forceKill() {
    try {
      if (processId != null) {
        if (Os.isWindows()) {
          Runtime.getRuntime().exec("taskkill /f /pid " + processId);
        } else if (Os.isLinux()) {
          Runtime.getRuntime().exec("kill -9 " + processId);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void cleanup() {
    FileUtils.deleteQuietly(new File(instanceDir, "bin"));
    FileUtils.deleteQuietly(new File(instanceDir, "config"));
    FileUtils.deleteQuietly(new File(instanceDir, "configuration"));
    FileUtils.deleteQuietly(new File(instanceDir, "etc"));
    FileUtils.deleteQuietly(new File(instanceDir, "firststeps"));
    FileUtils.deleteQuietly(new File(instanceDir, "installableApps"));
    FileUtils.deleteQuietly(new File(instanceDir, "installedApps"));
    FileUtils.deleteQuietly(new File(instanceDir, "installedConnectors"));
    FileUtils.deleteQuietly(new File(instanceDir, "installedFilters"));
    FileUtils.deleteQuietly(new File(instanceDir, "properties"));
    FileUtils.deleteQuietly(new File(instanceDir, "servers"));
    FileUtils.deleteQuietly(new File(instanceDir, "temp"));
    FileUtils.deleteQuietly(new File(instanceDir, "tranlog"));
    FileUtils.deleteQuietly(new File(instanceDir, "workspace"));
    FileUtils.deleteQuietly(new File(instanceDir, "wstemp"));

  }

  private void reset() throws Exception {
    // cleanup
    FileUtils.deleteQuietly(new File(serverInstallDir, "properties/profileRegistry.xml_LOCK"));
    FileUtils.deleteQuietly(new File(serverInstallDir, "properties/was.license"));
    FileUtils.deleteQuietly(new File(serverInstallDir, "logs"));

    synchronized (this) {
      if (!resetRepo) {
        // empty profile registry
        String emptyRegistry = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><profiles>" + StringUtil.LINE_SEPARATOR
                               + "</profiles>";
        writeProfileRegistry(emptyRegistry);
        resetRepo = true;
      }
    }
  }

  private String readProfileRegistry() throws Exception {
    FileReader reader = new FileReader(profileResistry);
    try {
      return IOUtils.toString(reader);
    } finally {
      IOUtils.closeQuietly(reader);
    }
  }

  private void writeProfileRegistry(String content) throws Exception {
    PrintWriter writer = new PrintWriter(profileResistry);
    try {
      writer.println(content);
    } finally {
      IOUtils.closeQuietly(writer);
    }
  }

  private void copyOperationLogs() {
    // copy websphere preparation logs
    File websphereLogs = new File(serverInstallDir, "logs");
    if (websphereLogs.exists() && websphereLogs.isDirectory()) {
      try {
        FileUtils.copyDirectoryToDirectory(websphereLogs, instanceDir);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
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

  private void createProfile() throws Exception {
    long start = System.currentTimeMillis();
    File profileTemplate = new File(profileRepo, appServerInfo().toString() + File.separator + "default");
    FileUtils.copyDirectory(profileTemplate, instanceDir);
    Collection<File> executables = FileUtils.listFiles(instanceDir, new String[] { "sh", "bat" }, true);
    for (File file : executables) {
      file.setExecutable(true);
    }

    String entry = "<profile isAReservationTicket=\"false\" " + "isDefault=\"false\" name=\"" + instanceName
                   + "\" path=\"" + instanceDir.getAbsolutePath() + "\" template=\""
                   + new File(serverInstallDir, "profileTemplates" + File.separator + "default").getAbsolutePath()
                   + "\"/>";

    addProfileEntry(entry);
    createProfileStartupFile();
    randomizePorts();
    long elapsedMillis = System.currentTimeMillis() - start;
    long elapsedSeconds = elapsedMillis / 1000;
    Long elapsedMinutes = new Long(elapsedSeconds / 60);
    System.out.println("Profile creation time: "
                       + MessageFormat.format("{0,number,##}:{1}.{2}", new Object[] { elapsedMinutes,
                           new Long(elapsedSeconds % 60), new Long(elapsedMillis % 1000) }));

  }

  private void createProfileStartupFile() throws Exception {
    String fileName = Os.isWindows() ? instanceName + ".bat" : instanceName + ".sh";
    File startupFile = new File(serverInstallDir, "properties/fsdb/" + fileName);
    startupFile.getParentFile().mkdirs();
    PrintWriter writer = new PrintWriter(startupFile);
    try {
      if (Os.isWindows()) {
        writer.println("set WAS_USER_SCRIPT=" + instanceDir.getAbsolutePath() + "\\bin\\setupCmdLine.bat");
      } else {
        writer.println("#!/bin/sh\nexport WAS_USER_SCRIPT=" + instanceDir.getAbsolutePath() + "/bin/setupCmdLine.sh");
      }
      startupFile.setExecutable(true);
    } finally {
      IOUtils.closeQuietly(writer);
    }
  }

  private void randomizePorts() throws Exception {
    List<String> tokens = Arrays.asList("@WC_defaulthost@", "@WC_adminhost@", "@WC_defaulthost_secure@",
                                        "@WC_adminhost_secure@", "@BOOTSTRAP_ADDRESS@", "@SOAP_CONNECTOR_ADDRESS@",
                                        "@IPC_CONNECTOR_ADDRESS@", "@SAS_SSL_SERVERAUTH_LISTENER_ADDRESS@",
                                        "@CSIV2_SSL_SERVERAUTH_LISTENER_ADDRESS@",
                                        "@CSIV2_SSL_MUTUALAUTH_LISTENER_ADDRESS@", "@ORB_LISTENER_ADDRESS@",
                                        "@DCS_UNICAST_ADDRESS@", "@SIB_ENDPOINT_ADDRESS@",
                                        "@SIB_ENDPOINT_SECURE_ADDRESS@", "@SIB_MQ_ENDPOINT_ADDRESS@",
                                        "@SIB_MQ_ENDPOINT_SECURE_ADDRESS@", "@SIP_DEFAULTHOST@",
                                        "@SIP_DEFAULTHOST_SECURE@", "@SERVERINSTALLDIR@", "@INSTANCEDIR@",
                                        "@INSANCENAME");

    for (String token : tokens) {
      Replace replaceTask = new Replace();
      replaceTask.setProject(new Project());
      replaceTask.setDir(instanceDir);
      replaceTask.setIncludes("**/*.xml,**/*.props,**/*.properties,**/*.sh,**/*.bat,**/*.metadata");
      replaceTask.setToken(token);
      String value = null;
      if ("@SERVERINSTALLDIR@".equals(token)) {
        value = serverInstallDir.getAbsolutePath().replace('\\', '/');
      } else if ("@INSTANCEDIR@".equals(token)) {
        value = instanceDir.getAbsolutePath().replace('\\', '/');
      } else if ("@INSTANCENAME".equals(token)) {
        value = instanceName;
      } else {
        value = String.valueOf(AppServerUtil.getPort());
      }
      if ("@WC_defaulthost@".equals(token)) {
        webspherePort = Integer.valueOf(value);
      }
      replaceTask.setValue(value);
      replaceTask.execute();
    }
  }

  private void addProfileEntry(String entry) throws Exception {
    String registry = readProfileRegistry();
    registry = registry.replace("</profiles>", entry + StringUtil.LINE_SEPARATOR + "</profiles>");
    writeProfileRegistry(registry);
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
    String output = executeCommand(serverInstallDir, "startServer", args, instanceDir, "Error in starting "
                                                                                       + instanceName);
    int index = output.indexOf("process id is");
    if (index > 0) {
      processId = output.substring(index + 13).trim();
    }
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
    serverInstallDir = serverInstallDirectory();
    profileRepo = new File(TestConfigObject.getInstance().cacheDir(), "websphere-profiles");
    profileResistry = new File(serverInstallDir, "properties" + File.separator + "profileRegistry.xml");

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
    String output = stdout.append(IOUtils.LINE_SEPARATOR).append(stderr).toString();
    System.out.println("output: " + output);
    boolean success = output.contains("INSTCONFPARTIALSUCCESS") || output.contains("INSTCONFSUCCESS");
    if (result.getExitCode() != 0 && !success) {
      //
      throw new RuntimeException("Command did not return 0: " + errorMessage);
    }
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
