/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.resin40x;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Replace;

import com.tc.process.Exec;
import com.tc.process.Exec.Result;
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
 * Resin40x AppServer implementation
 */
public final class Resin40xAppServer extends AbstractAppServer {
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

  public Resin40xAppServer(final Resin40xAppServerInstallation installation) {
    super(installation);
  }

  @Override
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

  @Override
  public void stop(final ServerParameters rawParams) throws Exception {
    final String[] cmd = new String[] { JAVA_CMD, "-jar",
        this.serverInstallDirectory() + File.separator + "lib" + File.separator + "resin.jar", "stop", "-conf",
        configFile, "-resin-home", serverInstallDirectory().getAbsolutePath() };

    try {
      System.err.println("Stopping instance " + instanceName + "...");
      Result result = Exec.execute(cmd, null, null, this.serverInstallDirectory());
      if (result.getExitCode() != 0) {
        System.err.println(result);
      }
      // we wait until watchdog port to shutdown before asserting the Resin server is down
      AppServerUtil.waitForPortToShutdown(watchdog_port, START_STOP_TIMEOUT);
      if (runner != null) {
        runner.join(START_STOP_TIMEOUT);
        if (runner.isAlive()) {
          System.err.println("Instance " + instanceName + " on port " + resin_port + " still alive.");
        } else {
          System.err.println("Resin instance " + instanceName + " stopped");
        }
      }
    } finally {
      FileUtils.deleteQuietly(new File(instanceDir, "resin-data"));
    }

  }

  private ServerResult startResin(final AppServerParameters params) throws Exception {
    prepareDeployment(params);

    List cmd = new ArrayList();
    cmd.add(0, JAVA_CMD);
    cmd.add("-jar");
    cmd.add(this.serverInstallDirectory() + File.separator + "lib" + File.separator + "resin.jar");
    cmd.add("start");
    cmd.add("-conf");
    cmd.add(configFile);
    cmd.add("-resin-home");
    cmd.add(this.serverInstallDirectory().getAbsolutePath());
    cmd.add("-root-directory");
    cmd.add(this.instanceDir.getAbsolutePath());
    final String[] cmdArray = (String[]) cmd.toArray(new String[] {});
    final File watchdogLog = new File(instanceDir, "log" + File.separator + "watchdog-manager.log");
    final String nodeLogFile = new File(instanceDir + ".log").getAbsolutePath();
    System.err.println("Starting resin with cmd: " + cmd);
    process = Runtime.getRuntime().exec(cmdArray, null, instanceDir);
    runner = new Thread("runner for " + instanceName) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(process, cmdArray, nodeLogFile, null, instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println("Command failed: " + result);
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
    try {

      while (System.currentTimeMillis() < timeout) {
        if (AppServerUtil.pingPort(watchdog_port)) {
          started = true;
          break;
        }

        if (watchdogLog.exists() && configExceptionCheck(watchdogLog)) { throw new RetryException(
                                                                                                  "thread-idle-max config exception"); }
      }

      if (!started) {
        if (!watchdogLog.exists()) throw new RetryException("watchdog log doesn't exist");
        if (configExceptionCheck(watchdogLog)) throw new RetryException("thread-idle-max config exception");
        throw new RuntimeException("Failed to start server in " + START_STOP_TIMEOUT + "ms");
      }
    } finally {
      if (!started) {
        process.destroy();
        runner.join(5 * 1000L);
      }
    }

    AppServerUtil.waitForPort(resin_port, START_STOP_TIMEOUT);
    System.err.println("Started " + instanceName + " on port " + resin_port);
    return new AppServerResult(resin_port, this);
  }

  protected static boolean configExceptionCheck(final File watchdogLog) throws IOException {
    // see MNK-2527
    List<CharSequence> hit1 = Grep.grep("at com.caucho.util.ThreadPool.setThreadIdleMin", watchdogLog);
    List<CharSequence> hit2 = Grep.grep("at com.caucho.util.ThreadPool.setThreadIdleMax", watchdogLog);

    return (!hit1.isEmpty() || !hit2.isEmpty());
  }

  private void prepareDeployment(final AppServerParameters params) throws Exception {
    instanceName = params.instanceName();
    instanceDir = new File(sandboxDirectory(), instanceName);
    ensureDirectory(instanceDir);
    ensureDirectory(getConfDirectory());

    File webapps_dir = getWebappsDirectory();
    File deploy_dir = getDeployDirectory();
    ensureDirectory(webapps_dir);

    // move wars into the correct location
    Map deployables = params.deployables();
    if (deployables != null && deployables.size() > 0) {
      Set entries = deployables.entrySet();
      Iterator it = entries.iterator();
      while (it.hasNext()) {
        Map.Entry war_entry = (Map.Entry) it.next();
        File deployableFile = (File) war_entry.getValue();
        if (deployableFile.getName().endsWith("war")) {
          FileUtils.copyFileToDirectory(deployableFile, webapps_dir);
        } else {
          FileUtils.copyFileToDirectory(deployableFile, deploy_dir);
        }
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

  private File getDeployDirectory() {
    return new File(instanceDir, "deploy");
  }

  private File getConfDirectory() {
    return new File(instanceDir, "conf");
  }

  private void createConfigFile(final String[] jvmargs) throws IOException {
    File confFile = new File(getConfDirectory(), "resin.xml");
    configFile = confFile.getAbsolutePath();
    copyResource("resin.xml", confFile);
    copyResource("admin-users.xml", new File(getConfDirectory(), "admin-users.xml"));
    copyResource("cluster-default.xml", new File(getConfDirectory(), "cluster-default.xml"));
    copyResource("health.xml", new File(getConfDirectory(), "health.xml"));
    replaceToken("@resin.servlet.port@", String.valueOf(resin_port), confFile);
    replaceToken("@resin.watchdog.port@", String.valueOf(watchdog_port), confFile);
    replaceToken("@resin.cluster.port@", String.valueOf(cluster_port), confFile);
    replaceToken("@resin.server.id@", instanceName, confFile);
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
