/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.resin31x;

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
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.util.AppServerUtil;
import com.tc.test.server.util.ParamsWithRetry;
import com.tc.test.server.util.RetryException;
import com.tc.text.Banner;
import com.tc.util.Grep;
import com.tc.util.PortChooser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resin3x AppServer implementation
 */
public final class Resin31xAppServer extends AbstractAppServer {
  private static final String JAVA_CMD           = System.getProperty("java.home") + File.separator + "bin"
                                                   + File.separator + "java";

  private static final long   START_STOP_TIMEOUT = 240 * 1000;
  public static final int     STARTUP_RETRIES    = 3;

  private String              configFile;
  private String              instanceName;
  private File                instanceDir;

  private int                 resin_port         = 0;
  private int                 watchdog_port      = 0;
  private int                 cluster_port       = 0;
  private Thread              runner             = null;
  private Process             process;

  public Resin31xAppServer(final Resin31xAppServerInstallation installation) {
    super(installation);
  }

  public ServerResult start(final ServerParameters parameters) throws Exception {
    AppServerParameters params = (AppServerParameters) parameters;
    for (int i = 0; i < STARTUP_RETRIES; i++) {
      try {
        return startResin(new ParamsWithRetry(params, i));
      } catch (RetryException re) {
        Banner.warnBanner("Re-trying server startup (" + i + ") " + re.getMessage());

        if (process != null) {
          try {
            process.destroy();
          } catch (Throwable t) {
            t.printStackTrace();
          }
        }

        continue;
      }
    }

    throw new RuntimeException("Failed to start server in " + STARTUP_RETRIES + " attempts");
  }

  public void stop(final ServerParameters rawParams) throws Exception {
    final String[] cmd = new String[] { JAVA_CMD, "-jar",
        this.serverInstallDirectory() + File.separator + "lib" + File.separator + "resin.jar", "stop", "-conf",
        configFile };

    System.err.println("Stopping instance " + instanceName + "...");
    Result result = Exec.execute(cmd, null, null, this.serverInstallDirectory());
    if (result.getExitCode() != 0) {
      System.err.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        System.err.println("Instance " + instanceName + " on port " + resin_port + " still alive.");
      } else {
        System.err.println("Resin instance " + instanceName + " stopped");
      }
    }

  }

  private ServerResult startResin(final AppServerParameters params) throws Exception {
    prepareDeployment(params);

    List cmd = new ArrayList();
    cmd.add(0, JAVA_CMD);
    cmd.add("-cp");
    cmd.add(TestConfigObject.getInstance().extraClassPathForAppServer());
    cmd.add("-jar");
    cmd.add(this.serverInstallDirectory() + File.separator + "lib" + File.separator + "resin.jar");
    cmd.add("start");
    cmd.add("-conf");
    cmd.add(configFile);
    cmd.add("-resin-home");
    cmd.add(this.serverInstallDirectory().getAbsolutePath());
    cmd.add("-root-directory");
    cmd.add(this.instanceDir.getAbsolutePath());
    cmd.add("-verbose");
    final String[] cmdArray = (String[]) cmd.toArray(new String[] {});
    final File watchdogLog = new File(instanceDir, "log" + File.separator + "watchdog-manager.log");
    final String nodeLogFile = new File(instanceDir + ".log").getAbsolutePath();
    System.err.println("Starting resin with cmd: " + cmd);
    process = Runtime.getRuntime().exec(cmdArray, null, instanceDir);
    runner = new Thread("runner for " + instanceName) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(cmdArray, nodeLogFile, null, instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println(result);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    System.err.println("Starting resin " + instanceName + " on port " + resin_port + "...");

    boolean started = false;
    long timeout = System.currentTimeMillis() + START_STOP_TIMEOUT;
    while (System.currentTimeMillis() < timeout) {
      if (AppServerUtil.pingPort(resin_port)) {
        started = true;
        break;
      }

      if (!runner.isAlive()) {
        if (configExceptionCheck(watchdogLog)) { throw new RetryException("NPE in AMXDebug"); }
        throw new RuntimeException("Runner thread finished before timeout");
      }
    }

    if (!started) { throw new RuntimeException("Failed to start server in " + START_STOP_TIMEOUT + "ms"); }

    AppServerUtil.waitForPort(resin_port, START_STOP_TIMEOUT);
    System.err.println("Started " + instanceName + " on port " + resin_port);
    return new AppServerResult(resin_port, this);
  }

  protected static boolean configExceptionCheck(final File nodeLogFile) throws IOException {
    // see MNK-2527
    List<CharSequence> hits = Grep
        .grep("^Exception in thread \"main\" com.caucho.config.ConfigException:", nodeLogFile);
    List<CharSequence> hits2 = Grep.grep("^\tat com.caucho.util.ThreadPool.setThreadIdleMax", nodeLogFile);

    return (!hits.isEmpty() && !hits2.isEmpty());
  }

  private void prepareDeployment(final AppServerParameters params) throws Exception {
    instanceName = params.instanceName();
    instanceDir = new File(sandboxDirectory(), instanceName);
    ensureDirectory(instanceDir);
    ensureDirectory(getConfDirectory());

    File webapps_dir = getWebappsDirectory();
    ensureDirectory(webapps_dir);

    // move wars into the correct location
    Map wars = params.wars();
    if (wars != null && wars.size() > 0) {
      Set war_entries = wars.entrySet();
      Iterator war_entries_it = war_entries.iterator();
      while (war_entries_it.hasNext()) {
        Map.Entry war_entry = (Map.Entry) war_entries_it.next();
        File war_file = (File) war_entry.getValue();
        FileUtils.copyFileToDirectory(war_file, webapps_dir);
      }
    }

    // setup deployment config
    PortChooser portChooser = new PortChooser();
    resin_port = portChooser.chooseRandomPort();
    watchdog_port = portChooser.chooseRandomPort();
    cluster_port = portChooser.chooseRandomPort();

    setProperties(params, resin_port, instanceDir);
    createConfigFile(params.jvmArgs().replaceAll("'", "").split("\\s+"));
  }

  private static void ensureDirectory(final File dir) throws Exception {
    if (!dir.exists() && dir.mkdirs() == false) { throw new Exception("Can't create directory ("
                                                                      + dir.getAbsolutePath()); }
  }

  private File getWebappsDirectory() {
    return new File(instanceDir, "webapps");
  }

  private File getConfDirectory() {
    return new File(instanceDir, "conf");
  }

  private void createConfigFile(final String[] jvmargs) throws IOException {
    File confFile = new File(getConfDirectory(), "resin.conf");
    configFile = confFile.getAbsolutePath();
    copyResource("resin.conf", confFile);
    replaceToken("@resin.servlet.port@", String.valueOf(resin_port), confFile);
    replaceToken("@resin.watchdog.port@", String.valueOf(watchdog_port), confFile);
    replaceToken("@resin.cluster.port@", String.valueOf(cluster_port), confFile);
    StringBuilder resinExtraJvmArgs = new StringBuilder();
    for (String ja : jvmargs) {
      resinExtraJvmArgs.append("<jvm-arg>").append(ja).append("</jvm-arg>").append("\n");
    }
    replaceToken("@resin.extra.jvmargs@", resinExtraJvmArgs.toString(), confFile);
  }

  private void copyResource(final String name, final File dest) throws IOException {
    InputStream in = getClass().getResourceAsStream(name);
    FileOutputStream out = new FileOutputStream(dest);
    try {
      IOUtils.copy(in, out);
    } finally {
      IOUtils.closeQuietly(in);
      IOUtils.closeQuietly(out);
    }
  }

  private void replaceToken(final String token, final String value, final File file) {
    Replace replaceTask = new Replace();
    replaceTask.setProject(new Project());
    replaceTask.setFile(file);
    replaceTask.setToken(token);
    replaceTask.setValue(value);
    replaceTask.execute();
  }
}
